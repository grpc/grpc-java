/*
 * Copyright 2024 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.integration.EmptyProtos.Empty;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for ManagedChannelImpl that use a real transport. */
@RunWith(JUnit4.class)
public final class ManagedChannelImplIntegrationTest {
  private static final String SERVER_NAME = ManagedChannelImplIntegrationTest.class.getName();
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Test
  public void idleWhileRpcInTransport_exitsIdleForNewRpc() throws Exception {
    FakeClock fakeClock = new FakeClock();
    grpcCleanup.register(InProcessServerBuilder.forName(SERVER_NAME)
        .directExecutor()
        .addService(
            ServerInterceptors.intercept(
                new TestServiceImpl(fakeClock.getScheduledExecutorService()),
                TestServiceImpl.interceptors()))
        .build()
        .start());
    ManagedChannel channel = grpcCleanup.register(InProcessChannelBuilder.forName(SERVER_NAME)
        .directExecutor()
        .build());

    TestServiceGrpc.TestServiceBlockingStub blockingStub = TestServiceGrpc.newBlockingStub(channel);
    TestServiceGrpc.TestServiceStub asyncStub = TestServiceGrpc.newStub(channel);
    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver =
        asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setIntervalUs(Integer.MAX_VALUE))
        .build());
    try {
      channel.enterIdle();
      assertThat(blockingStub
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .emptyCall(Empty.getDefaultInstance()))
          .isEqualTo(Empty.getDefaultInstance());
    } finally {
      requestObserver.onError(new RuntimeException("cleanup"));
    }
  }
}
