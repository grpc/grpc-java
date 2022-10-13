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
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Pattern;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class LoggingTest {

  @ClassRule
  public static final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "PROJECT";
  private static final ImmutableMap<String, String> LOCATION_TAGS = ImmutableMap.of(
      "project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");
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

  // UsedReflectively
  public static final class StaticTestingClassEndtoEndLogging implements Runnable {

    @Override
    public void run() {
      Sink sink =
          new GcpLogSink(
              PROJECT_ID, LOCATION_TAGS, CUSTOM_TAGS, Collections.emptySet());
      ObservabilityConfig config = mock(ObservabilityConfig.class);
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
                .addService(new LoggingTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
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
                .addService(new LoggingTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        verifyNoInteractions(spyLogHelper);
        verifyNoInteractions(mockSink);
      } catch (IOException e) {
        throw new AssertionError("Exception while testing logging event filter", e);
      }
    }
  }
}
