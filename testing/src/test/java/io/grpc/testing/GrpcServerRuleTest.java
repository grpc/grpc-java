/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.testing;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.helloworld.GreeterGrpc;
import io.grpc.testing.helloworld.HelloReply;
import io.grpc.testing.helloworld.HelloRequest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class GrpcServerRuleTest {

  public static class WithoutDirectExecutor {

    @Rule
    public final GrpcServerRule grpcServerRule = new GrpcServerRule();

    @Test
    public void serverAndChannelAreStarted() {
      assertThat(grpcServerRule.getServer().isShutdown()).isFalse();
      assertThat(grpcServerRule.getServer().isTerminated()).isFalse();

      assertThat(grpcServerRule.getChannel().isShutdown()).isFalse();
      assertThat(grpcServerRule.getChannel().isTerminated()).isFalse();

      assertThat(grpcServerRule.getServerName()).isNotNull();
      assertThat(grpcServerRule.getServiceRegistry()).isNotNull();
    }

    @Test
    public void serverAllowsServicesToBeAddedViaServiceRegistry() {
      MockGreeterImpl greeter = new MockGreeterImpl();

      grpcServerRule.getServiceRegistry().addService(greeter);

      GreeterGrpc.GreeterBlockingStub stub =
          GreeterGrpc.newBlockingStub(grpcServerRule.getChannel());

      HelloRequest request1 = HelloRequest.newBuilder()
          .setName(UUID.randomUUID().toString())
          .build();

      HelloRequest request2 = HelloRequest.newBuilder()
          .setName(UUID.randomUUID().toString())
          .build();

      HelloReply reply1 = stub.sayHello(request1);
      HelloReply reply2 = stub.sayHello(request2);

      assertThat(greeter.sayHelloRequests)
          .containsExactly(request1, request2);

      assertThat(reply1.getMessage()).isEqualTo(MockGreeterImpl.REPLY_PREFIX + request1.getName());
      assertThat(reply2.getMessage()).isEqualTo(MockGreeterImpl.REPLY_PREFIX + request2.getName());
    }
  }

  public static class WithDirectExecutor {

    @Rule
    public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();

    @Test
    public void serverAndChannelAreStarted() {
      assertThat(grpcServerRule.getServer().isShutdown()).isFalse();
      assertThat(grpcServerRule.getServer().isTerminated()).isFalse();

      assertThat(grpcServerRule.getChannel().isShutdown()).isFalse();
      assertThat(grpcServerRule.getChannel().isTerminated()).isFalse();

      assertThat(grpcServerRule.getServerName()).isNotNull();
      assertThat(grpcServerRule.getServiceRegistry()).isNotNull();
    }

    @Test
    public void serverAllowsServicesToBeAddedViaServiceRegistry() {
      MockGreeterImpl greeter = new MockGreeterImpl();

      grpcServerRule.getServiceRegistry().addService(greeter);

      GreeterGrpc.GreeterBlockingStub stub =
          GreeterGrpc.newBlockingStub(grpcServerRule.getChannel());

      HelloRequest request1 = HelloRequest.newBuilder()
          .setName(UUID.randomUUID().toString())
          .build();

      HelloRequest request2 = HelloRequest.newBuilder()
          .setName(UUID.randomUUID().toString())
          .build();

      HelloReply reply1 = stub.sayHello(request1);
      HelloReply reply2 = stub.sayHello(request2);

      assertThat(greeter.sayHelloRequests)
          .containsExactly(request1, request2);

      assertThat(reply1.getMessage()).isEqualTo(MockGreeterImpl.REPLY_PREFIX + request1.getName());
      assertThat(reply2.getMessage()).isEqualTo(MockGreeterImpl.REPLY_PREFIX + request2.getName());
    }
  }

  public static class ResourceCleanup {

    @Test
    public void serverAndChannelAreShutdownAfterRule() throws Throwable {
      GrpcServerRule grpcServerRule = new GrpcServerRule();

      // Before the rule has been executed, all of its resources should be null.
      assertThat(grpcServerRule.getChannel()).isNull();
      assertThat(grpcServerRule.getServer()).isNull();
      assertThat(grpcServerRule.getServerName()).isNull();
      assertThat(grpcServerRule.getServiceRegistry()).isNull();

      // The TestStatement stores the channel and server instances so that we can inspect them after
      // the rule cleans up.
      TestStatement statement = new TestStatement(grpcServerRule);

      grpcServerRule.apply(statement, null).evaluate();

      // Ensure that the stored channel and server instances were shut down.
      assertThat(statement.channel.isShutdown()).isTrue();
      assertThat(statement.server.isShutdown()).isTrue();

      // All references to the resources that we created should be set to null.
      assertThat(grpcServerRule.getChannel()).isNull();
      assertThat(grpcServerRule.getServer()).isNull();
      assertThat(grpcServerRule.getServerName()).isNull();
      assertThat(grpcServerRule.getServiceRegistry()).isNull();
    }

    private static class TestStatement extends Statement {

      private final GrpcServerRule grpcServerRule;

      private ManagedChannel channel;
      private Server server;

      private TestStatement(GrpcServerRule grpcServerRule) {
        this.grpcServerRule = grpcServerRule;
      }

      @Override
      public void evaluate() throws Throwable {
        channel = grpcServerRule.getChannel();
        server = grpcServerRule.getServer();
      }
    }
  }

  private static class MockGreeterImpl extends GreeterGrpc.GreeterImplBase {

    private static final String REPLY_PREFIX = "Hello ";

    private final Collection<HelloRequest> sayHelloRequests =
        new ConcurrentLinkedQueue<HelloRequest>();

    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
      // Keep track of the requests made so that we can assert them in the test.
      sayHelloRequests.add(request);

      // Send out a mock response.
      responseObserver.onNext(
          HelloReply.newBuilder()
              .setMessage(REPLY_PREFIX + request.getName())
              .buildPartial());

      responseObserver.onCompleted();
    }
  }
}
