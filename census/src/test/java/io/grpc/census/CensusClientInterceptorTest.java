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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.propagation.BinaryFormat;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link CensusClientInterceptor} and {@link CensusTracingModule}.
 */
@RunWith(JUnit4.class)
public class CensusClientInterceptorTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
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

  @Mock
  private Tracer tracer;
  @Mock
  private BinaryFormat mockTracingPropagationHandler;
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;

  private ManagedChannel channel;

  @Before
  public void setUp() {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .addService(
                ServerServiceDefinition.builder("package1.service2")
                    .addMethod(method, new ServerCallHandler<String, String>() {
                      @Override
                      public ServerCall.Listener<String> startCall(
                          ServerCall<String, String> call, Metadata headers) {
                        call.sendHeaders(new Metadata());
                        call.sendMessage("Hello");
                        call.close(
                            Status.PERMISSION_DENIED.withDescription("No you don't"), new Metadata());
                        return mockServerCallListener;
                      }
                    }).build())
            .directExecutor()
            .build());
    channel =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  @Test
  public void disableDefaultClientStatsByInterceptor() {
    ClientInterceptor interceptor =
        CensusClientInterceptor.newBuilder().setStatsEnabled(false).build();
    testDisableDefaultCensus(interceptor);
  }

  @Test
  public void disableDefaultClientTracingByInterceptor() {

  }

  private void testDisableDefaultCensus(ClientInterceptor interceptor) {
    final AtomicReference<CallOptions> capturedCallOptions = new AtomicReference<>();
    ClientInterceptor callOptionsCaptureInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        capturedCallOptions.set(callOptions);
        return next.newCall(method, callOptions);
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(channel, interceptor, callOptionsCaptureInterceptor);
    ClientCall<String, String> call = interceptedChannel.newCall(method, CALL_OPTIONS);
    assertThat(capturedCallOptions.get().getStreamTracerFactories()).isEmpty();
  }

  @Test
  public void stats_starts_finishes_realTime() {

  }

  @Test
  public void stats_starts_finishes_noReaLTime() {

  }

  @Test
  public void stats_starts_noFinishes_noRealTime() {

  }

  @Test
  public void stats_noStarts_finishes_noRealTime() {

  }

  @Test
  public void stats_noStarts_noFinishes_noRealTime() {

  }
}