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
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.InternalGlobalInterceptors;
import io.grpc.ManagedChannelProvider;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerProvider;
import io.grpc.StaticTestingClassLoader;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.logging.Sink;
import io.opencensus.trace.samplers.Samplers;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GcpObservabilityTest {

  @Test
  public void initFinish() throws Exception {
    ManagedChannelProvider prevChannelProvider = ManagedChannelProvider.provider();
    ServerProvider prevServerProvider = ServerProvider.provider();
    Sink sink = mock(Sink.class);
    ObservabilityConfig config = mock(ObservabilityConfig.class);
    InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
        mock(InternalLoggingChannelInterceptor.Factory.class);
    InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
        mock(InternalLoggingServerInterceptor.Factory.class);
    GcpObservability observability1;
    try (GcpObservability observability =
        GcpObservability.grpcInit(
            sink, config, channelInterceptorFactory, serverInterceptorFactory)) {
      assertThat(ManagedChannelProvider.provider()).isInstanceOf(LoggingChannelProvider.class);
      assertThat(ServerProvider.provider()).isInstanceOf(ServerProvider.class);
      observability1 =
          GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory);
      assertThat(observability1).isSameInstanceAs(observability);
    }
    verify(sink).close();
    assertThat(ManagedChannelProvider.provider()).isSameInstanceAs(prevChannelProvider);
    assertThat(ServerProvider.provider()).isSameInstanceAs(prevServerProvider);
    try {
      observability1.close();
      fail("should have failed for calling close() second time");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("GcpObservability already closed!");
    }
  }

  @Test
  public void enableObservability() throws Exception {
    StaticTestingClassLoader classLoader =
        new StaticTestingClassLoader(
            getClass().getClassLoader(), Pattern.compile("io\\.grpc\\.[^.]+"));
    Class<?> runnable = classLoader.loadClass(StaticTestingClassLoaderSet.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  // UsedReflectively
  public static final class StaticTestingClassLoaderSet implements Runnable {

    @Override
    public void run() {
      Sink sink = mock(Sink.class);
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      when(config.isEnableCloudLogging()).thenReturn(true);
      when(config.isEnableCloudMonitoring()).thenReturn(true);
      when(config.isEnableCloudTracing()).thenReturn(true);
      when(config.getSampler()).thenReturn(Samplers.neverSample());

      ClientInterceptor clientInterceptor =
          mock(ClientInterceptor.class, delegatesTo(new NoopClientInterceptor()));
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          mock(InternalLoggingChannelInterceptor.Factory.class);
      when(channelInterceptorFactory.create()).thenReturn(clientInterceptor);

      ServerInterceptor serverInterceptor =
          mock(ServerInterceptor.class, delegatesTo(new NoopServerInterceptor()));
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          mock(InternalLoggingServerInterceptor.Factory.class);
      when(serverInterceptorFactory.create()).thenReturn(serverInterceptor);

      try (GcpObservability observability =
          GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        assertThat(InternalGlobalInterceptors.getClientInterceptors()).hasSize(3);
        assertThat(InternalGlobalInterceptors.getServerInterceptors()).hasSize(1);
        assertThat(InternalGlobalInterceptors.getServerStreamTracerFactories()).hasSize(2);
      } catch (Exception e) {
        fail("Encountered exception: " + e);
      }
    }
  }

  private static class NoopClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return next.newCall(method, callOptions);
    }
  }

  private static class NoopServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(call, headers);
    }
  }
}
