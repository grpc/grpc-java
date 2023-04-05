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

package io.grpc.gcp.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StaticTestingClassLoader;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.interceptors.LogHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.gcp.observability.logging.TraceLoggingHelper;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opencensus.trace.SpanContext;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Pattern;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class LoggingTest {

  @ClassRule
  public static final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "PROJECT";
  private static final ImmutableMap<String, String> CUSTOM_TAGS = ImmutableMap.of(
      "KEY1", "Value1",
      "KEY2", "VALUE2");

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(getClass().getClassLoader(), Pattern.compile("io\\.grpc\\..*"));

  /**
   * Cloud logging test using GlobalInterceptors.
   *
   * <p> Ignoring test, because it calls external Cloud Logging APIs.
   * To test cloud logging setup locally,
   * 1. Set up Cloud auth credentials
   * 2. Assign permissions to service account to write logs to project specified by
   * variable PROJECT_ID
   * 3. Comment @Ignore annotation
   * 4. This test is expected to pass when ran with above setup. This has been verified manually.
   * </p>
   */
  @Ignore
  @Test
  public void clientServer_interceptorCalled_logAlways() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(LoggingTest.StaticTestingClassEndtoEndLogging.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void clientServer_interceptorCalled_logNever() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(LoggingTest.StaticTestingClassLogNever.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void clientServer_interceptorCalled_logEvents_usingMockSink() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(StaticTestingClassLogEventsUsingMockSink.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  // UsedReflectively
  public static final class StaticTestingClassEndtoEndLogging implements Runnable {

    @Override
    public void run() {
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      when(config.getCustomTags()).thenReturn(CUSTOM_TAGS);
      Sink sink =
          new GcpLogSink(
              PROJECT_ID, config, Collections.emptySet(),
              mock(TraceLoggingHelper.class));
      LogHelper spyLogHelper = spy(new LogHelper(sink));
      ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);

      when(config.isEnableCloudLogging()).thenReturn(true);
      FilterParams logAlwaysFilterParams = FilterParams.create(true, 1024, 10);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(true)))
          .thenReturn(logAlwaysFilterParams);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(false)))
          .thenReturn(logAlwaysFilterParams);

      try (GcpObservability unused =
          GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        assertThat(Mockito.mockingDetails(spyLogHelper).getInvocations().size()).isGreaterThan(11);
      } catch (IOException e) {
        throw new AssertionError("Exception while testing logging", e);
      }
    }
  }

  public static final class StaticTestingClassLogNever implements Runnable {

    @Override
    public void run() {
      Sink mockSink = mock(GcpLogSink.class);
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      LogHelper spyLogHelper = spy(new LogHelper(mockSink));
      ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper);

      when(config.isEnableCloudLogging()).thenReturn(true);
      FilterParams logNeverFilterParams = FilterParams.create(false, 0, 0);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(true)))
          .thenReturn(logNeverFilterParams);
      when(mockFilterHelper.logRpcMethod(anyString(), eq(false)))
          .thenReturn(logNeverFilterParams);

      try (GcpObservability unused =
          GcpObservability.grpcInit(
              mockSink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        verifyNoInteractions(spyLogHelper);
        verifyNoInteractions(mockSink);
      } catch (IOException e) {
        throw new AssertionError("Exception while testing logging event filter", e);
      }
    }
  }

  public static final class StaticTestingClassLogEventsUsingMockSink implements Runnable {

    @Override
    public void run() {
      Sink mockSink = mock(GcpLogSink.class);
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      LogHelper spyLogHelper = spy(new LogHelper(mockSink));
      ConfigFilterHelper mockFilterHelper2 = mock(ConfigFilterHelper.class);
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          new InternalLoggingChannelInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper2);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          new InternalLoggingServerInterceptor.FactoryImpl(spyLogHelper, mockFilterHelper2);

      when(config.isEnableCloudLogging()).thenReturn(true);
      FilterParams logAlwaysFilterParams = FilterParams.create(true, 0, 0);
      when(mockFilterHelper2.logRpcMethod(anyString(), eq(true)))
          .thenReturn(logAlwaysFilterParams);
      when(mockFilterHelper2.logRpcMethod(anyString(), eq(false)))
          .thenReturn(logAlwaysFilterParams);

      try (GcpObservability observability =
          GcpObservability.grpcInit(
              mockSink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        // Total number of calls should have been 14 (6 from client and 6 from server)
        // Since cancel is not invoked, it will be 12.
        // Request message(Total count:2 (1 from client and 1 from server) and Response
        // message(count:2)
        // events are not in the event_types list, i.e  14 - 2(cancel) - 2(req_msg) - 2(resp_msg)
        // = 8
        assertThat(Mockito.mockingDetails(mockSink).getInvocations().size()).isEqualTo(12);
        ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
        verify(mockSink, times(12)).write(captor.capture(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(),
                ArgumentMatchers.any(SpanContext.class)));
        for (GrpcLogRecord record : captor.getAllValues()) {
          assertThat(record.getType()).isInstanceOf(GrpcLogRecord.EventType.class);
          assertThat(record.getLogger()).isInstanceOf(GrpcLogRecord.EventLogger.class);
        }
      } catch (IOException e) {
        throw new AssertionError("Exception while testing logging using mock sink", e);
      }
    }
  }
}
