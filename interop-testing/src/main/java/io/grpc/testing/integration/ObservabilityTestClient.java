/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.testing.integration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.gcp.observability.GcpObservability;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;

/**
 * The Observability integration testing client
 */
public class ObservabilityTestClient {
  private static final Logger logger = Logger.getLogger(ObservabilityTestClient.class.getName());

  private TestServiceGrpc.TestServiceBlockingStub blockingStub;
  private TestServiceGrpc.TestServiceStub asyncStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public ObservabilityTestClient(Channel channel) {
    // 'channel' here is a Channel, not a ManagedChannel, so it is not this code's responsibility to
    // shut it down.

    // Passing Channels to code makes code easier to test and makes it easier to reuse Channels.
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
    asyncStub = TestServiceGrpc.newStub(channel);
  }

  public void doFullDuplexCall() throws InterruptedException {
    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(5);
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
            @Override
            public void onNext(StreamingOutputCallResponse response) {
              logger.info("fullDuplexCall client receives request size " +
                  response.getPayload().getBody().size());
              queue.add(response);
            }
            @Override
            public void onError(Throwable t) {
              logger.info("fullDuplexCall client receives onError");
              System.out.println(t);
              queue.add(t);
            }
            @Override
            public void onCompleted() {
              logger.info("fullDuplexCall client receives completed");
              queue.add("Completed");
            }
          });

    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(314159))
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    for (int i = 0; i < 5; i++) {
      requestObserver.onNext(request);
      Object actualResponse = queue.poll(5000, TimeUnit.MILLISECONDS);
      if (actualResponse instanceof Throwable) {
        throw new AssertionError(actualResponse);
      }
    }
    requestObserver.onCompleted();
    logger.info("Final value in queue: "+queue.poll(5000, TimeUnit.MILLISECONDS));
  }

  public void doUnaryCall() {
    final byte[] trailingBytes =
        {(byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb};
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    SimpleResponse response;
    try {
      Metadata metadata = new Metadata();
      metadata.put(ObservabilityTestUtil.RPC_METADATA_KEY, "o11y-header-value");
      metadata.put(ObservabilityTestUtil.RPC_METADATA_BIN_KEY, trailingBytes);
      AtomicReference<Metadata> headersCapture = new AtomicReference<>();
      AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
      blockingStub = blockingStub.withInterceptors(
          MetadataUtils.newAttachHeadersInterceptor(metadata),
          MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));
      response = blockingStub.unaryCall(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Received response size: " + response.getPayload().getBody().size());
  }

  public static void main(String[] args) throws Exception {
    // Access a service running on the local machine on port 10000
    String target = "localhost:10000";
    int exportInterval = 0;
    String actions[] = {"doUnaryCall"};
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [target [exportInterval [action]]]");
        System.err.println("");
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.err.println("  exportInterval  Number of seconds to wait for exporting observability data. Defaults to " + exportInterval);
        System.err.println("  action  The action to perform. Defaults to " + actions[0]);
        System.exit(1);
      }
      target = args[0];
      if (args.length > 1) {
        exportInterval = Integer.parseInt(args[1]);
      }
      if (args.length > 2) {
        actions = args[2].split(",");
      }
    }

    GcpObservability observability = GcpObservability.grpcInit();

    // Create a communication channel to the server, known as a Channel. Channels are thread-safe
    // and reusable. It is common to create channels at the beginning of your application and reuse
    // them until the application shuts down.
    ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .build();
    try {
      ObservabilityTestClient client = new ObservabilityTestClient(channel);
      for (String action : actions) {
        if (action.equals("doFullDuplexCall")) {
          try {
            client.doFullDuplexCall();
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        } else { // "doUnaryCall"
          client.doUnaryCall();
        }
      }
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    // call close() on the observability instance to shutdown observability
    logger.info("calling observability.close() after "+exportInterval+"s sleep");
    int ss = exportInterval;
    while (ss > 0) {
      int next = Math.min(ss, 15);
      Thread.sleep(TimeUnit.MILLISECONDS.convert(next, TimeUnit.SECONDS));
      ss = ss - next;
      logger.info(ss+"s more to sleep");
    }
    observability.close();
  }
}
