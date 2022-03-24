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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.interceptors.ConfigFilterHelper;
import io.grpc.observability.interceptors.ConfigFilterHelper.MethodFilterParams;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.observability.interceptors.LogHelper;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.util.Map;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class LoggingTest {

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "PROJECT";
  private static final Map<String, String> locationTags = ImmutableMap.of(
      "project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");
  private static final Map<String, String> customTags = ImmutableMap.of(
      "KEY1", "Value1",
      "KEY2", "VALUE2");

  /**
   * Cloud logging test using LoggingChannelProvider and LoggingServerProvider.
   */
  @Ignore
  @Test
  public void clientServer_interceptorCalled_logAlways()
      throws IOException {
    Sink sink = new GcpLogSink(PROJECT_ID);
    LogHelper spyLogHelper = spy(new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER,
        locationTags, customTags));
    ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
    MethodFilterParams logAlwaysFilterParams =
        new MethodFilterParams(true, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged(anyString()))
        .thenReturn(logAlwaysFilterParams);
    LoggingServerProvider.init(
        new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
        .usePlaintext().build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(channel));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    assertTrue("spyLogHelper should be used invoked six times from both client and server "
            + "interceptors respectively",
        Mockito.mockingDetails(spyLogHelper).getInvocations().size() >= 12);
    sink.close();
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
  }

  @Test
  public void clientServer_interceptorCalled_logNever() throws IOException {
    Sink spySink = spy(new GcpLogSink(PROJECT_ID));
    LogHelper spyLogHelper = spy(new LogHelper(spySink, TimeProvider.SYSTEM_TIME_PROVIDER,
        locationTags, customTags));
    ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
    MethodFilterParams logNeverFilterParams =
        new MethodFilterParams(false, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged(anyString()))
        .thenReturn(logNeverFilterParams);
    LoggingServerProvider.init(
        new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
        .usePlaintext().build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(channel));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    verifyNoInteractions(spyLogHelper);
    verifyNoInteractions(spySink);
    spySink.close();
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
  }
}
