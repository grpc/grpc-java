/*
 * Copyright 2016 The gRPC Authors
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
import com.google.rpc.Code;
import com.google.rpc.DebugInfo;
import com.google.rpc.Status;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterBlockingStub;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterFutureStub;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterStub;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Shows how to set and read com.google.rpc.Status objects as google.rpc.Status error details.
 */
public class DetailErrorSample {
  private static final Metadata.Key<DebugInfo> DEBUG_INFO_TRAILER_KEY =
      ProtoUtils.keyForProto(DebugInfo.getDefaultInstance());

  private static final DebugInfo DEBUG_INFO =
      DebugInfo.newBuilder()
          .addStackEntries("stack_entry_1")
          .addStackEntries("stack_entry_2")
          .addStackEntries("stack_entry_3")
          .setDetail("detailed error info.").build();

  private static final String DEBUG_DESC = "detailed error description";

  public static void main(String[] args) throws Exception {
    new DetailErrorSample().run();
  }

  private ManagedChannel channel;

  void run() throws Exception {
    Server server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
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
    }).build().start();
    channel = Grpc.newChannelBuilderForAddress(
          "localhost", server.getPort(), InsecureChannelCredentials.create()).build();

    blockingCall();
    futureCallDirect();
    futureCallCallback();
    asyncCall();

    channel.shutdown();
    server.shutdown();
    channel.awaitTermination(1, TimeUnit.SECONDS);
    server.awaitTermination();
  }

  static void verifyErrorReply(Throwable t) {
    Status status = StatusProto.fromThrowable(t);
    Verify.verify(status.getCode() == Code.INVALID_ARGUMENT.getNumber());
    Verify.verify(status.getMessage().equals("Email or password malformed"));
    Verify.verify(status.getDetails(0).equals(DEBUG_DESC));
  }

  void blockingCall() {
    GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
    try {
      stub.sayHello(HelloRequest.newBuilder().build());
    } catch (Exception e) {
      verifyErrorReply(e);
    }
  }

  void futureCallDirect() {
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
    }
  }

  void futureCallCallback() {
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
            latch.countDown();
          }
        },
        directExecutor());

    if (!Uninterruptibles.awaitUninterruptibly(latch, 1, TimeUnit.SECONDS)) {
      throw new RuntimeException("timeout!");
    }
  }

  void asyncCall() {
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

