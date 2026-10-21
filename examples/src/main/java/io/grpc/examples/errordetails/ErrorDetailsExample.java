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

package io.grpc.examples.errordetails;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import com.google.rpc.DebugInfo;
import com.google.rpc.Status;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterBlockingStub;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterFutureStub;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterStub;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Shows how to set and read com.google.rpc.Status objects as google.rpc.Status error details.
 */
public class ErrorDetailsExample {
  private static final DebugInfo DEBUG_INFO =
      DebugInfo.newBuilder()
          .addStackEntries("stack_entry_1")
          .addStackEntries("stack_entry_2")
          .addStackEntries("stack_entry_3")
          .setDetail("detailed error info.").build();

  public static void main(String[] args) throws Exception {
    Server server = null;
    ManagedChannel channel = null;

    try {
      server = launchServer();
      channel = Grpc.newChannelBuilderForAddress(
          "localhost", server.getPort(), InsecureChannelCredentials.create()).build();

      runClientTests(channel);
    } finally {
      cleanup(channel, server);
    }
  }


  /**
   * Create server and start it
   */
  static Server launchServer() throws Exception {
    return Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
        .addService(new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            // This is com.google.rpc.Status, not io.grpc.Status
            Status status = Status.newBuilder()
                .setCode(Code.INVALID_ARGUMENT.getNumber())
                .setMessage("Email or password malformed")
                .addDetails(Any.pack(DEBUG_INFO))
                .build();
            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
          }
        })
        .build()
        .start();
  }

  private static void runClientTests(Channel channel) {
    blockingCall(channel);
    futureCallDirect(channel);
    futureCallCallback(channel);
    asyncCall(channel);
  }

  private static void cleanup(ManagedChannel channel, Server server) throws InterruptedException {

    // Shutdown client and server for resources to be cleanly released
    if (channel != null) {
      channel.shutdown();
    }
    if (server != null) {
      server.shutdown();
    }

    // Wait for cleanup to complete
    if (channel != null) {
      channel.awaitTermination(1, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  static void verifyErrorReply(Throwable t) {
    Status status = StatusProto.fromThrowable(t);
    Verify.verify(status.getCode() == Code.INVALID_ARGUMENT.getNumber());
    Verify.verify(status.getMessage().equals("Email or password malformed"));
    try {
      DebugInfo unpackedDetail = status.getDetails(0).unpack(DebugInfo.class);
      Verify.verify(unpackedDetail.equals(DEBUG_INFO));
    } catch (InvalidProtocolBufferException e) {
      Verify.verify(false, "Message was a different type than expected");
    }
  }

  static void blockingCall(Channel channel) {
    GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
    try {
      stub.sayHello(HelloRequest.newBuilder().build());
    } catch (Exception e) {
      verifyErrorReply(e);
      System.out.println("Blocking call received expected error details");
    }
  }

  static void futureCallDirect(Channel channel) {
    GreeterFutureStub stub = GreeterGrpc.newFutureStub(channel);
    ListenableFuture<HelloReply> response =
        stub.sayHello(HelloRequest.newBuilder().build());

    try {
      response.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      verifyErrorReply(e.getCause());
      System.out.println("Future call direct received expected error details");
    }
  }

  static void futureCallCallback(Channel channel) {
    GreeterFutureStub stub = GreeterGrpc.newFutureStub(channel);
    ListenableFuture<HelloReply> response =
        stub.sayHello(HelloRequest.newBuilder().build());

    final CountDownLatch latch = new CountDownLatch(1);

    Futures.addCallback(
        response,
        new FutureCallback<HelloReply>() {
          @Override
          public void onSuccess(@Nullable HelloReply result) {
            // Won't be called, since the server in this example always fails.
          }

          @Override
          public void onFailure(Throwable t) {
            verifyErrorReply(t);
            System.out.println("Future callback received expected error details");
            latch.countDown();
          }
        },
        directExecutor());

    if (!Uninterruptibles.awaitUninterruptibly(latch, 1, TimeUnit.SECONDS)) {
      throw new RuntimeException("timeout!");
    }
  }

  static void asyncCall(Channel channel) {
    GreeterStub stub = GreeterGrpc.newStub(channel);
    HelloRequest request = HelloRequest.newBuilder().build();
    final CountDownLatch latch = new CountDownLatch(1);
    StreamObserver<HelloReply> responseObserver = new StreamObserver<HelloReply>() {

      @Override
      public void onNext(HelloReply value) {
        // Won't be called.
      }

      @Override
      public void onError(Throwable t) {
        verifyErrorReply(t);
        System.out.println("Async call received expected error details");
        latch.countDown();
      }

      @Override
      public void onCompleted() {
        // Won't be called, since the server in this example always fails.
      }
    };
    stub.sayHello(request, responseObserver);

    if (!Uninterruptibles.awaitUninterruptibly(latch, 1, TimeUnit.SECONDS)) {
      throw new RuntimeException("timeout!");
    }
  }
}

