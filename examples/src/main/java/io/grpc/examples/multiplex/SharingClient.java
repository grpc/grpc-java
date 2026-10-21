/*
 * Copyright 2023 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.multiplex;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractFuture;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.echo.EchoGrpc;
import io.grpc.examples.echo.EchoRequest;
import io.grpc.examples.echo.EchoResponse;
import io.grpc.examples.helloworld.HelloWorldClient;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;


/**
 * A client that shares a channel across multiple stubs to a single service and across services
 * being provided by one server process.
 */
public class SharingClient {
  private static final Logger logger = Logger.getLogger(
      HelloWorldClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub greeterStub1;
  private final GreeterGrpc.GreeterBlockingStub greeterStub2;
  private final EchoGrpc.EchoStub echoStub;

  private Random random = new Random();

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public SharingClient(Channel channel) {
    // 'channel' here is a Channel, not a ManagedChannel, so it is not this code's responsibility to
    // shut it down.

    // Passing Channels to code makes code easier to test and makes it easier to reuse Channels.
    greeterStub1 = GreeterGrpc.newBlockingStub(channel);
    greeterStub2 = GreeterGrpc.newBlockingStub(channel);
    echoStub = EchoGrpc.newStub(channel);
  }

  /** Say hello to server. */
  private void greet(String name, GreeterGrpc.GreeterBlockingStub stub, String stubName)
      throws InterruptedException {
    System.out.println("Will try to greet " + name + " using " + stubName);
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = stub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    System.out.println("Greeting: " + response.getMessage());
    // pause to allow interleaving
    Thread.sleep(1000);
  }

  public void greet1(String name) throws InterruptedException {
    greet(name, greeterStub1, "greeter #1");
  }

  public void greet2(String name) throws InterruptedException {
    greet(name, greeterStub2, "greeter #2");
  }

  public StreamingFuture<List<String>> initiateEchos(List<String> valuesToSend) {
    StreamingFuture<List<String>> future = new StreamingFuture<List<String>> ();
    List<String> valuesReceived = new ArrayList<>();

    // The logic that gets called by the framework during the RPC's lifecycle
    StreamObserver<EchoResponse> responseObserver = new StreamObserver<EchoResponse>() {
      @Override
      public void onNext(EchoResponse response) {
        System.out.println("Received an echo: " + response.getMessage());
        valuesReceived.add(response.getMessage());
      }

      @Override
      public void onError(Throwable t) {
        logger.warning("Echo Failed: {0}" + Status.fromThrowable(t));
        future.setException(t);
      }

      @Override
      public void onCompleted() {
        System.out.println("Server acknowledged end of echo stream.");
        future.set(valuesReceived);
      }
    };

    future.setObserver(responseObserver);

    new Thread(new Runnable() {
      public void run() {
        StreamObserver<EchoRequest> requestObserver =
            echoStub.bidirectionalStreamingEcho(responseObserver);

        try {
          for (String curValue : valuesToSend) {
            System.out.println("Sending an echo request for: " + curValue);
            EchoRequest req = EchoRequest.newBuilder().setMessage(curValue).build();
            requestObserver.onNext(req);

            // Sleep for a bit before sending the next one.
            Thread.sleep(random.nextInt(1000) + 500);
          }
        } catch (RuntimeException e) {
          // Cancel RPC
          requestObserver.onError(e);
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          requestObserver.onError(e);
          return;
        }

        // Mark the end of requests
        requestObserver.onCompleted();
      }
    }).start();

    return future;
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting. The second argument is the target server.
   * You can see the multiplexing in the server logs.
   */
  public static void main(String[] args) throws Exception {
    String user = "world";
    // Access a service running on the local machine on port 50051
    String target = "localhost:50051";
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [name [target]]");
        System.err.println("");
        System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      user = args[0];
    }
    if (args.length > 1) {
      target = args[1];
    }

    // Create a communication channel to the server, known as a Channel. Channels are thread-safe
    // and reusable. It is common to create channels at the beginning of your application and reuse
    // them until the application shuts down.
    //
    // For the example we use plaintext insecure credentials to avoid needing TLS certificates. To
    // use TLS, use TlsChannelCredentials instead.
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    List<String> echoInput = ImmutableList.of("some", "thing", "wicked", "this", "way", "comes");
    try {
      SharingClient client = new SharingClient(channel);

      StreamingFuture<List<String>> future = client.initiateEchos(echoInput);
      client.greet1(user + " the great");
      client.greet2(user + " the lesser");
      client.greet1(user + " the humble");
      // Receiving happens asynchronously

      String resultStr = future.get(1, TimeUnit.MINUTES).toString();
      System.out.println("The echo requests and results were:");
      System.out.println(echoInput.toString());
      System.out.println(resultStr);

      if (!future.isDone()) {
        System.err.println("Streaming rpc failed to complete in 1 minute");
      }
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private class StreamingFuture<RespT> extends AbstractFuture<RespT> {

    private StreamObserver<EchoResponse> responseObserver = null;

    private void setObserver(StreamObserver<EchoResponse> responseObserver) {
      this.responseObserver = responseObserver;
    }

    @Override
    protected void interruptTask() {
      if (responseObserver != null) {
        responseObserver.onError(Status.ABORTED.asException());
      }

    }

    // These are needed for visibility from the parent object
    @Override
    protected boolean set(@Nullable RespT resp) {
      return super.set(resp);
    }

    @Override
    protected boolean setException(Throwable throwable) {
      return super.setException(throwable);
    }

  }
}
