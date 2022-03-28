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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LoggingTest {

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "project-id";

  /**
   * Cloud logging test using LoggingChannelProvider and LoggingServerProvider.
   */
  @Ignore
  @Test
  public void clientServer_interceptorCalled()
      throws IOException {
    Sink sink = new GcpLogSink(PROJECT_ID);
    LoggingServerProvider.init(
        new InternalLoggingServerInterceptor.FactoryImpl(sink, null, null, null));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(sink, null, null, null));
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
        .usePlaintext().build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(channel));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    sink.close();
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
  }
}
