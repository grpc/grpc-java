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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.InternalGlobalInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StaticTestingClassLoader;
import io.grpc.gcp.observability.interceptors.ConditionalClientInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.logging.Sink;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GcpObservabilityTest {

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(
          getClass().getClassLoader(),
          Pattern.compile(
              "io\\.grpc\\.InternalGlobalInterceptors|io\\.grpc\\.GlobalInterceptors|"
                  + "io\\.grpc\\.gcp\\.observability\\.[^.]+|"
                  + "io\\.grpc\\.gcp\\.observability\\.interceptors\\.[^.]+|"
                  + "io\\.grpc\\.gcp\\.observability\\.GcpObservabilityTest\\$.*"));

  @Test
  public void initFinish() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(StaticTestingClassInitFinish.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void enableObservability() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(StaticTestingClassEnableObservability.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  public void disableObservability() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(StaticTestingClassDisableObservability.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void conditionalInterceptor() {
    ClientInterceptor delegate = mock(ClientInterceptor.class);
    Channel channel = mock(Channel.class);
    ClientCall<?, ?> returnedCall = mock(ClientCall.class);

    ConditionalClientInterceptor conditionalClientInterceptor
        = GcpObservability.getConditionalInterceptor(
        delegate);
    MethodDescriptor<?, ?> method = MethodDescriptor.newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("google.logging.v2.LoggingServiceV2/method")
        .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
        .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
        .build();
    doReturn(returnedCall).when(channel).newCall(method, CallOptions.DEFAULT);
    ClientCall<?, ?> clientCall = conditionalClientInterceptor.interceptCall(method,
        CallOptions.DEFAULT, channel);
    verifyNoInteractions(delegate);
    assertThat(clientCall).isSameInstanceAs(returnedCall);

    method = MethodDescriptor.newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("google.monitoring.v3.MetricService/method2")
        .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
        .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
        .build();
    doReturn(returnedCall).when(channel).newCall(method, CallOptions.DEFAULT);
    clientCall = conditionalClientInterceptor.interceptCall(method, CallOptions.DEFAULT, channel);
    verifyNoInteractions(delegate);
    assertThat(clientCall).isSameInstanceAs(returnedCall);

    method = MethodDescriptor.newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("google.devtools.cloudtrace.v2.TraceService/method3")
        .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
        .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
        .build();
    doReturn(returnedCall).when(channel).newCall(method, CallOptions.DEFAULT);
    clientCall = conditionalClientInterceptor.interceptCall(method, CallOptions.DEFAULT, channel);
    verifyNoInteractions(delegate);
    assertThat(clientCall).isSameInstanceAs(returnedCall);

    reset(channel);
    ClientCall<?, ?> interceptedCall = mock(ClientCall.class);
    method = MethodDescriptor.newBuilder().setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("some.other.random.service/method4")
        .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
        .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
        .build();
    doReturn(interceptedCall).when(delegate).interceptCall(method, CallOptions.DEFAULT, channel);
    clientCall = conditionalClientInterceptor.interceptCall(method, CallOptions.DEFAULT, channel);
    verifyNoInteractions(channel);
    assertThat(clientCall).isSameInstanceAs(interceptedCall);
  }

  // UsedReflectively
  public static final class StaticTestingClassInitFinish implements Runnable {

    @Override
    public void run() {
      Sink sink = mock(Sink.class);
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          mock(InternalLoggingChannelInterceptor.Factory.class);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          mock(InternalLoggingServerInterceptor.Factory.class);
      GcpObservability observability1;
      try {
        GcpObservability observability =
            GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory);
        observability1 =
            GcpObservability.grpcInit(
                sink, config, channelInterceptorFactory, serverInterceptorFactory);
        assertThat(observability1).isSameInstanceAs(observability);
        observability.close();
        verify(sink).close();
        try {
          observability1.close();
          fail("should have failed for calling close() second time");
        } catch (IllegalStateException e) {
          assertThat(e).hasMessageThat().contains("GcpObservability already closed!");
        }
      } catch (IOException e) {
        fail("Encountered exception: " + e);
      }
    }
  }

  public static final class StaticTestingClassEnableObservability implements Runnable {

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

      try (GcpObservability unused =
          GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        List<ClientInterceptor> list = InternalGlobalInterceptors.getClientInterceptors();
        assertThat(list).hasSize(3);
        assertThat(list.get(1)).isInstanceOf(ConditionalClientInterceptor.class);
        assertThat(list.get(2)).isInstanceOf(ConditionalClientInterceptor.class);
        assertThat(InternalGlobalInterceptors.getServerInterceptors()).hasSize(1);
        assertThat(InternalGlobalInterceptors.getServerStreamTracerFactories()).hasSize(2);
      } catch (Exception e) {
        fail("Encountered exception: " + e);
      }
    }
  }

  public static final class StaticTestingClassDisableObservability implements Runnable {

    @Override
    public void run() {
      Sink sink = mock(Sink.class);
      ObservabilityConfig config = mock(ObservabilityConfig.class);
      when(config.isEnableCloudLogging()).thenReturn(false);
      when(config.isEnableCloudMonitoring()).thenReturn(false);
      when(config.isEnableCloudTracing()).thenReturn(false);
      when(config.getSampler()).thenReturn(Samplers.neverSample());

      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory =
          mock(InternalLoggingChannelInterceptor.Factory.class);
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory =
          mock(InternalLoggingServerInterceptor.Factory.class);

      try (GcpObservability unused =
          GcpObservability.grpcInit(
              sink, config, channelInterceptorFactory, serverInterceptorFactory)) {
        assertThat(InternalGlobalInterceptors.getClientInterceptors()).isEmpty();
        assertThat(InternalGlobalInterceptors.getServerInterceptors()).isEmpty();
        assertThat(InternalGlobalInterceptors.getServerStreamTracerFactories()).isEmpty();
      } catch (Exception e) {
        fail("Encountered exception: " + e);
      }
      verify(sink).close();
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
