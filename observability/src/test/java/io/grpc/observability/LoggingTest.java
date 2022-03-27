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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.interceptors.ConfigFilterHelper;
import io.grpc.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.observability.interceptors.LogHelper;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
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
  private static final int flushLimit = 100;

  /**
   * Cloud logging test using LoggingChannelProvider and LoggingServerProvider.
   * 
   * <p> Ignoring test, cos it calls external CLoud Logging APIs which is not required everytime
   * we build.
   * To test cloud logging setup, 
   * 1. Set up Cloud Logging Auth credentials
   * 2. Assign permissions to service account to write logs to project specified by 
   * variable PROJECT_ID
   * 3. Comment @Ignore annotation
   * </p>
   */
  @Ignore
  @Test
  public void clientServer_interceptorCalled_logAlways()
      throws IOException {
    Sink sink = new GcpLogSink(PROJECT_ID, locationTags, customTags, flushLimit);
    LogHelper spyLogHelper = spy(new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER));
    ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
    FilterParams logAlwaysFilterParams =
        FilterParams.create(true, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged(any(MethodDescriptor.class)))
        .thenReturn(logAlwaysFilterParams);
    when(mockFilterHelper.isEventToBeLogged(any(GrpcLogRecord.EventType.class)))
        .thenReturn(true);
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
    Sink mockSink = mock(GcpLogSink.class);
    LogHelper spyLogHelper = spy(new LogHelper(mockSink, TimeProvider.SYSTEM_TIME_PROVIDER));
    ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
    FilterParams logNeverFilterParams =
        FilterParams.create(false, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged(any(MethodDescriptor.class)))
        .thenReturn(logNeverFilterParams);
    when(mockFilterHelper.isEventToBeLogged(any(GrpcLogRecord.EventType.class)))
        .thenReturn(true);
    LoggingServerProvider.init(
        new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper));
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext().build()));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    verifyNoInteractions(spyLogHelper);
    verifyNoInteractions(mockSink);
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
  }

  @Test
  public void clientServer_interceptorCalled_doNotLogMessageEvents() throws IOException {
    LogHelper mockLogHelper = mock(LogHelper.class);
    ConfigFilterHelper mockFilterHelper2 = mock(ConfigFilterHelper.class);
    FilterParams logAlwaysFilterParams =
        FilterParams.create(true, 0, 0);
    when(mockFilterHelper2.isMethodToBeLogged(any(MethodDescriptor.class)))
        .thenReturn(logAlwaysFilterParams);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_REQUEST_HEADER))
        .thenReturn(true);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_RESPONSE_HEADER))
        .thenReturn(true);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_HALF_CLOSE))
        .thenReturn(true);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_TRAILER))
        .thenReturn(true);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_CANCEL))
        .thenReturn(true);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_REQUEST_MESSAGE))
        .thenReturn(false);
    when(mockFilterHelper2.isEventToBeLogged(EventType.GRPC_CALL_RESPONSE_MESSAGE))
        .thenReturn(false);
    LoggingServerProvider.init(
        new InternalLoggingServerInterceptor.FactoryImpl(mockLogHelper, mockFilterHelper2));
    Server server = ServerBuilder.forPort(0).addService(new LoggingTestHelper.SimpleServiceImpl())
        .build().start();
    int port = cleanupRule.register(server).getPort();
    LoggingChannelProvider.init(
        new InternalLoggingChannelInterceptor.FactoryImpl(mockLogHelper, mockFilterHelper2));
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
        .usePlaintext().build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(channel));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
        .isEqualTo("Hello buddy");
    assertTrue("LogHelper should be invoked eight times equal to number of events "
            + "excluding request message and response message events",
        Mockito.mockingDetails(mockLogHelper).getInvocations().size() == 8 );
    System.out.println(Mockito.mockingDetails(mockLogHelper).getInvocations().size());
    LoggingChannelProvider.shutdown();
    LoggingServerProvider.shutdown();
  }
}
