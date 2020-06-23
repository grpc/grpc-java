/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.census;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InternalInProcess;
import io.grpc.testing.GrpcCleanupRule;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link CensusClientInterceptor}.
 */
@RunWith(JUnit4.class)
public class CensusClientInterceptorTest {

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.createWithDefault("option", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue");

  private static class StringInputStream extends InputStream {
    final String string;

    StringInputStream(String string) {
      this.string = string;
    }

    @Override
    public int read() {
      // InProcessTransport doesn't actually read bytes from the InputStream.  The InputStream is
      // passed to the InProcess server and consumed by MARSHALLER.parse().
      throw new UnsupportedOperationException("Should not be called");
    }
  }

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new StringInputStream(value);
        }

        @Override
        public String parse(InputStream stream) {
          return ((StringInputStream) stream).string;
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("package1.service2/method3")
          .build();

  private final AtomicReference<CallOptions> callOptionsCaptor = new AtomicReference<>();

  private ManagedChannel channel;


  @SuppressWarnings("unchecked")
  @Before
  public void setUp() {
    ClientInterceptor callOptionCaptureInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        callOptionsCaptor.set(callOptions);
        return next.newCall(method, callOptions);
      }
    };
    InProcessChannelBuilder builder =
        InProcessChannelBuilder.forName("non-existing server").directExecutor();
    InternalInProcess.setTestInterceptor(builder, callOptionCaptureInterceptor);
    channel = grpcCleanupRule.register(builder.build());
  }

  @Test
  public void usingCensusInterceptorDisablesDefaultCensus() {
    ClientInterceptor interceptor =
        CensusClientInterceptor.newBuilder().build();
    Channel interceptedChannel = ClientInterceptors.intercept(channel, interceptor);
    interceptedChannel.newCall(method, CALL_OPTIONS);
    assertThat(callOptionsCaptor.get().getStreamTracerFactories()).isEmpty();
  }

  @Test
  public void customStatsConfig() {
    ClientInterceptor interceptor =
        CensusClientInterceptor.newBuilder()
            .setStatsEnabled(true)
            .setRecordStartedRpcs(false)
            .setRecordFinishedRpcs(false)
            .setRecordRealTimeMetrics(true)
            .build();
    Channel interceptedChannel = ClientInterceptors.intercept(channel, interceptor);
    interceptedChannel.newCall(method, CALL_OPTIONS);
    CensusStatsModule.ClientCallTracer callTracer =
        (CensusStatsModule.ClientCallTracer) Iterables.getOnlyElement(
            callOptionsCaptor.get().getStreamTracerFactories());
    assertThat(callTracer.module.recordStartedRpcs).isEqualTo(false);
    assertThat(callTracer.module.recordFinishedRpcs).isEqualTo(false);
    assertThat(callTracer.module.recordRealTimeMetrics).isEqualTo(true);
  }

  @Test
  public void onlyEnableTracing() {
    ClientInterceptor interceptor =
        CensusClientInterceptor.newBuilder().setTracingEnabled(true).build();
    Channel interceptedChannel = ClientInterceptors.intercept(channel, interceptor);
    interceptedChannel.newCall(method, CALL_OPTIONS);
    assertThat(Iterables.getOnlyElement(callOptionsCaptor.get().getStreamTracerFactories()))
        .isInstanceOf(CensusTracingModule.ClientCallTracer.class);
  }

  @Test
  public void enableStatsAndTracing() {
    ClientInterceptor interceptor =
        CensusClientInterceptor.newBuilder().setStatsEnabled(true).setTracingEnabled(true).build();
    Channel interceptedChannel = ClientInterceptors.intercept(channel, interceptor);
    interceptedChannel.newCall(method, CALL_OPTIONS);
    assertThat(callOptionsCaptor.get().getStreamTracerFactories()).hasSize(2);
    assertThat(callOptionsCaptor.get().getStreamTracerFactories().get(0))
        .isInstanceOf(CensusTracingModule.ClientCallTracer.class);
    assertThat(callOptionsCaptor.get().getStreamTracerFactories().get(1))
        .isInstanceOf(CensusStatsModule.ClientCallTracer.class);
  }
}
