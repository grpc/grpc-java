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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.io.ByteStreams;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.HeaderForwardingRules;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.MetricRecorder;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterOverrideConfig;
import io.grpc.xds.Filter.FilterContext;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ExternalProcessorServerInterceptorTest {
  private static final Context.Key<String> TRACE_KEY = Context.key("trace-id");

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private String extProcServerName;
  private ExternalProcessorFilter.Provider provider;
  private static final FilterContext FAKE_CONTEXT =
      FilterContext.create("test-filter", new io.grpc.MetricRecorder() {});
  private Filter.FilterConfigParseContext filterContext;
  private Bootstrapper.BootstrapInfo bootstrapInfo;
  private Bootstrapper.ServerInfo serverInfo;

  private static class InputStreamMarshaller implements MethodDescriptor.Marshaller<InputStream> {
    @Override
    public InputStream stream(InputStream value) {
      try {
        byte[] bytes = ByteStreams.toByteArray(value);
        return new ByteArrayInputStream(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public InputStream parse(InputStream stream) {
      try {
        byte[] bytes = ByteStreams.toByteArray(stream);
        return new ByteArrayInputStream(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class LazyInputStreamMarshaller implements MethodDescriptor.Marshaller<InputStream> {
    @Override
    public InputStream stream(InputStream value) {
      return value;
    }

    @Override
    public InputStream parse(InputStream stream) {
      return stream;
    }
  }

  private static final MethodDescriptor<InputStream, InputStream> METHOD_SAY_HELLO_RAW =
      MethodDescriptor.<InputStream, InputStream>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.TestService/SayHello")
          .setRequestMarshaller(new InputStreamMarshaller())
          .setResponseMarshaller(new InputStreamMarshaller())
          .build();

  private static final MethodDescriptor<InputStream, InputStream> METHOD_SAY_HELLO_RAW_LAZY =
      MethodDescriptor.<InputStream, InputStream>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.TestService/SayHelloLazy")
          .setRequestMarshaller(new LazyInputStreamMarshaller())
          .setResponseMarshaller(new LazyInputStreamMarshaller())
          .build();

  private static final MethodDescriptor<InputStream, InputStream> METHOD_SAY_HELLO_BIDI =
      MethodDescriptor.<InputStream, InputStream>newBuilder()
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .setFullMethodName("test.TestService/SayHelloBidi")
          .setRequestMarshaller(new InputStreamMarshaller())
          .setResponseMarshaller(new InputStreamMarshaller())
          .build();

  private static final MethodDescriptor<InputStream, InputStream>
      METHOD_SAY_HELLO_CLIENT_STREAMING =
          MethodDescriptor.<InputStream, InputStream>newBuilder()
              .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName("test.TestService/SayHelloClientStreaming")
              .setRequestMarshaller(new InputStreamMarshaller())
              .setResponseMarshaller(new InputStreamMarshaller())
              .build();

  private String dataPlaneServerName;
  private io.grpc.Channel dataPlaneChannel;

  private interface DataPlaneServiceHandler {
    default void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
      responseObserver.onNext(request);
      responseObserver.onCompleted();
    }

    default StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
      return new StreamObserver<InputStream>() {
        @Override
        public void onNext(InputStream value) {
          responseObserver.onNext(value);
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    default StreamObserver<InputStream> sayHelloClientStreaming(
        StreamObserver<InputStream> responseObserver) {
      return new StreamObserver<InputStream>() {
        private InputStream lastValue;

        @Override
        public void onNext(InputStream value) {
          lastValue = value;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onNext(lastValue);
          responseObserver.onCompleted();
        }
      };
    }
  }

  private volatile DataPlaneServiceHandler dataPlaneHandler = new DataPlaneServiceHandler() {};

  private void startDataPlane(ServerInterceptor... interceptors) throws Exception {
    dataPlaneServerName = InProcessServerBuilder.generateName();
    ServerServiceDefinition dataPlaneService =
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_SAY_HELLO_RAW,
                io.grpc.stub.ServerCalls.asyncUnaryCall(
                    (request, responseObserver) ->
                        dataPlaneHandler.sayHello(request, responseObserver)))
            .addMethod(
                METHOD_SAY_HELLO_RAW_LAZY,
                io.grpc.stub.ServerCalls.asyncUnaryCall(
                    (request, responseObserver) ->
                        dataPlaneHandler.sayHello(request, responseObserver)))
            .addMethod(
                METHOD_SAY_HELLO_BIDI,
                io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
                    responseObserver -> dataPlaneHandler.sayHelloBidi(responseObserver)))
            .addMethod(
                METHOD_SAY_HELLO_CLIENT_STREAMING,
                io.grpc.stub.ServerCalls.asyncClientStreamingCall(
                    responseObserver -> dataPlaneHandler.sayHelloClientStreaming(responseObserver)))
            .build();

    grpcCleanup.register(
        InProcessServerBuilder.forName(dataPlaneServerName)
            .addService(
                ServerInterceptors.intercept(
                    dataPlaneService, java.util.Arrays.asList(interceptors)))
            .directExecutor()
            .build()
            .start());

    dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());
  }

  private static class SimpleServerCall extends ServerCall<InputStream, InputStream> {
    private final MethodDescriptor<InputStream, InputStream> method;

    SimpleServerCall(MethodDescriptor<InputStream, InputStream> method) {
      this.method = method;
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(InputStream message) {}

    @Override
    public void close(Status status, Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<InputStream, InputStream> getMethodDescriptor() {
      return method;
    }
  }

  private static class InProcessNameResolverProvider extends NameResolverProvider {
    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      if ("in-process".equals(targetUri.getScheme())) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "localhost";
          }

          @Override
          public void start(Listener2 listener) {}

          @Override
          public void shutdown() {}
        };
      }
      return null;
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    @Override
    public String getDefaultScheme() {
      return "in-process";
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return Collections.emptyList();
    }
  }

  @Before
  public void setUp() throws Exception {
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_SERVER", "true");
    ExternalProcessorServerInterceptor.initMetricInstruments();
    NameResolverRegistry.getDefaultRegistry().register(new InProcessNameResolverProvider());

    extProcServerName = InProcessServerBuilder.generateName();
    provider = new ExternalProcessorFilter.Provider();

    bootstrapInfo =
        Bootstrapper.BootstrapInfo.builder()
            .node(Node.newBuilder().build())
            .servers(
                Collections.singletonList(
                    Bootstrapper.ServerInfo.create("test_target", Collections.emptyMap())))
            .build();

    serverInfo =
        Bootstrapper.ServerInfo.create(
            "test_target", Collections.emptyMap(), false, true, false, false, null);

    filterContext =
        Filter.FilterConfigParseContext.builder()
            .bootstrapInfo(bootstrapInfo)
            .serverInfo(serverInfo)
            .build();
  }

  private ExternalProcessor.Builder createBaseProto(String targetName) {
    return ExternalProcessor.newBuilder()
        .setGrpcService(
            GrpcService.newBuilder()
                .setGoogleGrpc(
                    GrpcService.GoogleGrpc.newBuilder()
                        .setTargetUri("in-process:///" + targetName)
                        .addChannelCredentialsPlugin(
                            Any.newBuilder()
                                .setTypeUrl(
                                    "type.googleapis.com/envoy.extensions.grpc_service."
                                        + "channel_credentials.insecure.v3.InsecureCredentials")
                                .build())
                        .build())
                .build());
  }

  // ============================================================================
  // Category 1: Configuration Override
  // ============================================================================

  @Test
  public void givenOverrideConfig_whenGrpcServiceOverridden_thenUsesNewService() throws Exception {
    ExternalProcessor parentProto =
        createBaseProto(extProcServerName)
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///parent")
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();

    GrpcService overrideService =
        GrpcService.newBuilder()
            .setGoogleGrpc(
                GrpcService.GoogleGrpc.newBuilder()
                    .setTargetUri("in-process:///override")
                    .addChannelCredentialsPlugin(
                        Any.newBuilder()
                            .setTypeUrl(
                                "type.googleapis.com/envoy.extensions.grpc_service."
                                    + "channel_credentials.insecure.v3.InsecureCredentials")
                            .build())
                    .build())
            .build();
    io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute perRoute =
        io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute.newBuilder()
            .setOverrides(
                io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides
                    .newBuilder()
                    .setGrpcService(overrideService)
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult =
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult =
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorServerInterceptor interceptor =
        (ExternalProcessorServerInterceptor)
            filter.buildServerInterceptor(parentConfig, overrideConfig);

    assertThat(
            interceptor
                .getFilterConfig()
                .getExternalProcessor()
                .getGrpcService()
                .getGoogleGrpc()
                .getTargetUri())
        .isEqualTo("in-process:///override");
  }

  @Test
  public void givenNoOverrideConfig_whenBuildServerInterceptor_thenUsesParentConfig() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName).build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult =
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorServerInterceptor interceptor =
        (ExternalProcessorServerInterceptor)
            filter.buildServerInterceptor(parentConfig, null);

    assertThat(interceptor.getFilterConfig()).isSameInstanceAs(parentConfig);
  }

  // ============================================================================
  // Category 2: Server Interceptor & Lifecycle
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenInterceptor_whenCallIntercepted_thenExtProcStubUsesSynchronizationContext()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicReference<java.util.concurrent.Executor> capturedExecutor = new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                if (method.equals(ExternalProcessorGrpc.getProcessMethod())) {
                                  capturedExecutor.set(callOptions.getExecutor());
                                }
                                return next.newCall(method, callOptions);
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueDataPlaneServerName)
            .fallbackHandlerRegistry(uniqueRegistry)
            .directExecutor()
            .build()
            .start());

    io.grpc.ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          return new ServerCall.Listener<InputStream>() {};
        };

    try {
      interceptor.interceptCall(
          new SimpleServerCall(METHOD_SAY_HELLO_RAW), new Metadata(), nextHandler);

      assertThat(capturedExecutor.get()).isNotNull();
      assertThat(capturedExecutor.get()).isSameInstanceAs(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    } finally {
      if (responseObserverRef.get() != null) {
        responseObserverRef.get().onCompleted();
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithTimeout_whenCallIntercepted_thenExtProcStubHasCorrectDeadline()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(5).build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicReference<io.grpc.Deadline> capturedDeadline = new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                if (method.equals(ExternalProcessorGrpc.getProcessMethod())) {
                                  capturedDeadline.set(callOptions.getDeadline());
                                }
                                return next.newCall(method, callOptions);
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueDataPlaneServerName)
            .fallbackHandlerRegistry(uniqueRegistry)
            .directExecutor()
            .build()
            .start());

    io.grpc.ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          return new ServerCall.Listener<InputStream>() {};
        };

    try {
      interceptor.interceptCall(
          new SimpleServerCall(METHOD_SAY_HELLO_RAW), new Metadata(), nextHandler);

      assertThat(capturedDeadline.get()).isNotNull();
      assertThat(capturedDeadline.get().timeRemaining(TimeUnit.SECONDS)).isAtLeast(4);
    } finally {
      if (responseObserverRef.get() != null) {
        responseObserverRef.get().onCompleted();
      }
      channelManager.close();
    }
  }

  // ============================================================================
  // Category 3: Protocol config propagation
  // ============================================================================

  @Test
  public void protocolConfig_onHeaders() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // Expecting request headers and request body
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          return new ServerCall.Listener<InputStream>() {};
        };

    SimpleServerCall dummyCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW);

    try {
      @SuppressWarnings("unchecked")
      ServerCall.Listener<InputStream> serverListener =
          (ServerCall.Listener<InputStream>)
              (ServerCall.Listener<?>)
                  interceptor.interceptCall(dummyCall, new Metadata(), nextHandler);

      // Invoke onMessage() while the call is IDLE (headers response has not been sent)
      byte[] messageBytes = "hello".getBytes(StandardCharsets.UTF_8);
      serverListener.onMessage(new ByteArrayInputStream(messageBytes));

      assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(capturedRequests).hasSize(2);

      // First request (RequestHeaders) should have protocol_config
      ProcessingRequest firstReq = capturedRequests.get(0);
      assertThat(firstReq.hasRequestHeaders()).isTrue();
      assertThat(firstReq.hasProtocolConfig()).isTrue();
      assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
          .isEqualTo(ProcessingMode.BodySendMode.GRPC);
      assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
          .isEqualTo(ProcessingMode.BodySendMode.GRPC);

      // Second request (RequestBody) should NOT have protocol_config
      ProcessingRequest secondReq = capturedRequests.get(1);
      assertThat(secondReq.hasRequestBody()).isTrue();
      assertThat(secondReq.hasProtocolConfig()).isFalse();
    } finally {
      StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
      if (responseObserver != null) {
        responseObserver.onCompleted();
      }
      channelManager.close();
    }
  }

  @Test
  public void protocolConfig_onBody() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(BodyResponse.newBuilder().build())
                          .build());
                }
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          return new ServerCall.Listener<InputStream>() {};
        };

    SimpleServerCall dummyCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW);
    try {
      ServerCall.Listener<InputStream> serverListener =
          interceptor.interceptCall(dummyCall, new Metadata(), nextHandler);

      byte[] messageBytes = "hello".getBytes(StandardCharsets.UTF_8);
      serverListener.onMessage(new ByteArrayInputStream(messageBytes));

      assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(capturedRequests).hasSize(1);

      ProcessingRequest firstReq = capturedRequests.get(0);
      assertThat(firstReq.hasRequestBody()).isTrue();
      assertThat(firstReq.hasProtocolConfig()).isTrue();
      assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
          .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    } finally {
      StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
      if (responseObserver != null) {
        responseObserver.onCompleted();
      }
      channelManager.close();
    }
  }

  @Test
  public void protocolConfig_onResponseHeaders() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(capturedRequests).hasSize(1);
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseHeaders()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
  }

  @Test
  public void protocolConfig_onResponseBody() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(
                                                              request.getResponseBody().getBody())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(capturedRequests.size()).isAtLeast(1);
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseBody()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);

    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }
  }

  @Test
  public void protocolConfig_onResponseTrailers() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(capturedRequests).hasSize(1);
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseTrailers()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
  }

  // ============================================================================
  // Category 4: GrpcService Initial Metadata
  // ============================================================================

  @Test
  public void givenGrpcServiceWithInitialMetadata_whenCallIntercepted_thenSendsMetadata()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    final CountDownLatch extProcStartedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    ServerInterceptor headerCapturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            extProcStartedLatch.countDown();
            return next.startCall(call, headers);
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(ServerInterceptors.intercept(extProcImpl, headerCapturingInterceptor))
            .directExecutor()
            .build()
            .start());

    // Config with initial metadata
    ExternalProcessor.Builder protoBuilder = createBaseProto(uniqueExtProcServerName);
    protoBuilder.setProcessingMode(
        ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .build());
    protoBuilder
        .getGrpcServiceBuilder()
        .addInitialMetadata(
            io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                .setKey("x-init-key")
                .setValue("init-val")
                .build())
        .addInitialMetadata(
            io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                .setKey("x-bin-key-bin")
                .setRawValue(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .build());
    ExternalProcessor proto = protoBuilder.build();

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> callStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    boolean sidecarAwaited = extProcStartedLatch.await(5, TimeUnit.SECONDS);
    boolean completedAwaited = callCompletedLatch.await(5, TimeUnit.SECONDS);

    assertThat(sidecarAwaited).isTrue();
    assertThat(completedAwaited).isTrue();
    assertThat(callStatus.get().isOk()).isTrue();

    assertThat(capturedHeaders.get()).isNotNull();
    assertThat(
            capturedHeaders
                .get()
                .get(Metadata.Key.of("x-init-key", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("init-val");
    assertThat(
            capturedHeaders
                .get()
                .get(Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER)))
        .isEqualTo(new byte[] {1, 2, 3});

  }

  // ============================================================================
  // Category 5: Request attributes propagation
  // ============================================================================

  @Test
  public void requestAttributes_onHeaders() throws Exception {
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasRequestTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    // Config with request attributes requested
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .addRequestAttributes("request.path")
            .addRequestAttributes("request.host")
            .addRequestAttributes("request.method")
            .addRequestAttributes("request.query")
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.path").getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    assertThat(attributes.get("request.host").getFieldsOrThrow("").getStringValue())
        .isEqualTo(dataPlaneChannel.authority());
    assertThat(attributes.get("request.method").getFieldsOrThrow("").getStringValue())
        .isEqualTo("POST");
    assertThat(attributes.get("request.query").getFieldsOrThrow("").getStringValue()).isEqualTo("");
  }

  @Test
  public void requestAttributes_metadata() throws Exception {
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasRequestTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    // Config with metadata attributes requested
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .addRequestAttributes("request.referer")
            .addRequestAttributes("request.useragent")
            .addRequestAttributes("request.id")
            .addRequestAttributes("request.headers")
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("referer", Metadata.ASCII_STRING_MARSHALLER), "http://google.com");
    headers.put(Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER), "custom-ua");
    headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), "req-123");
    headers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "val");
    headers.put(
        Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[] {1, 2});

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        headers);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.referer").getFieldsOrThrow("").getStringValue())
        .isEqualTo("http://google.com");
    assertThat(attributes.get("request.useragent").getFieldsOrThrow("").getStringValue())
        .contains("custom-ua");
    assertThat(attributes.get("request.id").getFieldsOrThrow("").getStringValue())
        .isEqualTo("req-123");

    com.google.protobuf.Struct headersStruct = attributes.get("request.headers");
    assertThat(headersStruct.getFieldsOrThrow("custom-header").getStringValue()).isEqualTo("val");
    assertThat(headersStruct.getFieldsOrThrow("x-bin-key-bin").getStringValue()).isEqualTo("AQI");
  }

  @Test
  public void requestAttributes_onBody() throws Exception {
    final java.util.List<ProcessingRequest> capturedRequests =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getRequestBody().getBody().isEmpty()
                      && (request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(request.getRequestBody().getBody())
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestBody(bodyResponse).build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .addRequestAttributes("request.path")
            .addRequestAttributes("request.host")
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(capturedRequests.size()).isAtLeast(1);
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestBody()).isTrue();
    java.util.Map<String, com.google.protobuf.Struct> attributes = firstReq.getAttributesMap();
    assertThat(attributes.get("request.path").getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    assertThat(attributes.get("request.host").getFieldsOrThrow("").getStringValue())
        .isEqualTo(dataPlaneChannel.authority());

    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).getAttributesCount()).isEqualTo(0);
    }
  }

  @Test
  public void requestAttributes_notSent() throws Exception {
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .addRequestAttributes("request.path")
            .addRequestAttributes("request.host")
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest request = capturedRequest.get();
    assertThat(request.hasResponseHeaders()).isTrue();
    assertThat(request.getAttributesCount()).isEqualTo(0);
  }

  // ============================================================================
  // Category 6: Request Header Processing
  // ============================================================================
  @Test
  public void givenRequestHeaderModeSend_whenStartCallCalled_thenCallIsBuffered() throws Exception {
    // Configure GRPC request body mode
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<ProcessingRequest> receivedRequestRef = new AtomicReference<>();
    final CountDownLatch requestLatch = new CountDownLatch(2); // Expect 1 for headers, 1 for body
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  receivedRequestRef.set(request);
                }
                requestLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    SimpleServerCall dummyCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW);

    final AtomicBoolean startCallCalled = new AtomicBoolean();
    ServerCallHandler<InputStream, InputStream> dummyNext =
        new ServerCallHandler<InputStream, InputStream>() {
          @Override
          public ServerCall.Listener<InputStream> startCall(
              ServerCall<InputStream, InputStream> call, Metadata headers) {
            startCallCalled.set(true);
            return new ServerCall.Listener<InputStream>() {};
          }
        };

    @SuppressWarnings("unchecked")
    ServerCall.Listener<InputStream> serverListener =
        (ServerCall.Listener<InputStream>)
            (ServerCall.Listener<?>)
                interceptor.interceptCall(dummyCall, new Metadata(), dummyNext);

    // Invocate onMessage() while the call is IDLE (headers response has not been sent)
    byte[] messageBytes = "hello".getBytes(StandardCharsets.UTF_8);
    serverListener.onMessage(new ByteArrayInputStream(messageBytes));

    // Wait and verify that the external processor immediately receives both the headers request and
    // the body request
    boolean receivedInTime = requestLatch.await(5, TimeUnit.SECONDS);
    assertThat(receivedInTime).isTrue();

    ProcessingRequest receivedRequest = receivedRequestRef.get();
    assertThat(receivedRequest).isNotNull();
    assertThat(receivedRequest.hasRequestBody()).isTrue();
    assertThat(receivedRequest.getRequestBody().getBody().toStringUtf8()).isEqualTo("hello");

    // Assert that the data plane call was not started yet (buffered)
    assertThat(startCallCalled.get()).isFalse();

    // Clean up control stream to allow resources to be released cleanly
    StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
    if (responseObserver != null) {
      responseObserver.onCompleted();
    }
  }

  @Test
  public void givenRequestHeaderModeSend_whenExtProcRespondsWithMutations_thenCallIsActivated()
      throws Exception {
    // Configure GRPC request body mode
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch requestLatch =
        new CountDownLatch(1); // Just wait for headers request to be processed

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            responseObserverRef.set(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                requestLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    SimpleServerCall dummyCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW);

    final AtomicBoolean startCallCalled = new AtomicBoolean();
    ServerCallHandler<InputStream, InputStream> dummyNext =
        new ServerCallHandler<InputStream, InputStream>() {
          @Override
          public ServerCall.Listener<InputStream> startCall(
              ServerCall<InputStream, InputStream> call, Metadata headers) {
            startCallCalled.set(true);
            return new ServerCall.Listener<InputStream>() {};
          }
        };

    @SuppressWarnings("unchecked")
    ServerCall.Listener<InputStream> serverListener =
        (ServerCall.Listener<InputStream>)
            (ServerCall.Listener<?>)
                interceptor.interceptCall(dummyCall, new Metadata(), dummyNext);

    // Invocate onMessage() while the call is IDLE (headers response has not been sent)
    byte[] messageBytes = "hello".getBytes(StandardCharsets.UTF_8);
    serverListener.onMessage(new ByteArrayInputStream(messageBytes));

    boolean receivedInTime = requestLatch.await(5, TimeUnit.SECONDS);
    assertThat(receivedInTime).isTrue();

    // Assert that the data plane call was not started yet
    assertThat(startCallCalled.get()).isFalse();

    // Clean up control stream by completing it, which triggers fallback activation on the data
    // plane call
    StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
    assertThat(responseObserver).isNotNull();
    responseObserver.onNext(
        ProcessingResponse.newBuilder().setRequestDrain(true).build());
    responseObserver.onCompleted();

    // Verify that the call is now activated
    assertThat(startCallCalled.get()).isTrue();
  }

  @Test
  public void serverInterceptor_headerMutation_addsHeader() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("x-mutated-header")
                                                                  .setValue("mutated-value")
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        };

    startDataPlane(interceptor, capturingInterceptor);

    Metadata initialHeaders = new Metadata();
    initialHeaders.put(
        Metadata.Key.of("x-initial-header", Metadata.ASCII_STRING_MARSHALLER), "initial-value");

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        initialHeaders);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedHeaders.get()).isNotNull();
    assertThat(
            receivedHeaders
                .get()
                .get(Metadata.Key.of("x-mutated-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated-value");
  }

  @Test
  public void serverInterceptor_requestHeaderModeSkip_doesNotSendHeaders() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicBoolean requestHeadersReceived = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  requestHeadersReceived.set(true);
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                extProcLatch.countDown();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        };

    startDataPlane(interceptor, capturingInterceptor);

    Metadata initialHeaders = new Metadata();
    initialHeaders.put(
        Metadata.Key.of("x-initial-header", Metadata.ASCII_STRING_MARSHALLER), "initial-value");

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        initialHeaders);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(requestHeadersReceived.get()).isFalse();
    assertThat(receivedHeaders.get()).isNotNull();
    assertThat(
            receivedHeaders
                .get()
                .get(Metadata.Key.of("x-initial-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("initial-value");
  }

  // ============================================================================
  // Category 7: Body Mutation: Inbound/Request (GRPC Mode)
  // ============================================================================

  @Test
  public void givenRequestBodyModeGrpc_whenMessageReceived_thenMessageSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch bodySentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  if (capturedRequest.get() == null
                      && !request.getRequestBody().getBody().isEmpty()) {
                    capturedRequest.set(request);
                    bodySentLatch.countDown();
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setEndOfStream(
                                                              request
                                                                  .getRequestBody()
                                                                  .getEndOfStream())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
    clientCall.request(1);
    clientCall.sendMessage(
        new ByteArrayInputStream("Hello World".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(bodySentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().getRequestBody().getBody().toStringUtf8())
        .contains("Hello World");

    clientCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void givenRequestBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyForwarded()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getRequestBody().getBody().isEmpty()
                      && (request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(
                                                ByteString.copyFromUtf8("Mutated Request Body"))
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestBody(bodyResponse).build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config  ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            try {
              byte[] bytes = ByteStreams.toByteArray(request);
              receivedBody.set(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedBody.set("Error reading: " + e.getMessage());
            }
            responseObserver.onNext(
                new ByteArrayInputStream("Hello Back".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            dataPlaneLatch.countDown();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Original".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBody.get()).isEqualTo("Mutated Request Body");

    clientCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void
      givenRequestBodyModeGrpc_whenExtProcRespondsWithEmptyBody_thenEmptyMessageIsDelivered()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getRequestBody().getBody().isEmpty()
                      && (request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(ByteString.EMPTY) // Mutate to EMPTY!
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestBody(bodyResponse).build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            try {
              byte[] bytes = ByteStreams.toByteArray(request);
              receivedBody.set(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedBody.set("Error reading: " + e.getMessage());
            }
            responseObserver.onNext(
                new ByteArrayInputStream("Hello Back".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            dataPlaneLatch.countDown();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Original".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBody.get()).isEmpty(); // Assert it is EMPTY!

    clientCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void givenExtProcSignaledEndOfStream_whenMoreMessagesReceived_thenMessagesDiscarded()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  sidecarMessages.incrementAndGet();
                  boolean triggerEos =
                      request.getRequestBody().getBody().toStringUtf8().equals("Trigger EOS");
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (triggerEos
                      || (request.getRequestBody().getBody().isEmpty()
                          && (request.getRequestBody().getEndOfStream()
                              || request.getRequestBody().getEndOfStreamWithoutMessage()))) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(request.getRequestBody().getBody())
                                            .setEndOfStream(
                                                triggerEos
                                                    || request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestBody(bodyResponse).build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicInteger dataPlaneMessages = new AtomicInteger(0);
    final CountDownLatch dataPlaneHalfCloseLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloClientStreaming(
              final StreamObserver<InputStream> responseObserver) {
            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream request) {
                try {
                  byte[] unused = ByteStreams.toByteArray(request);
                  dataPlaneMessages.incrementAndGet();
                } catch (Exception e) {
                  // Ignore
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onNext(
                    new ByteArrayInputStream("Hello Back".getBytes(StandardCharsets.UTF_8)));
                responseObserver.onCompleted();
                dataPlaneHalfCloseLatch.countDown();
              }
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_CLIENT_STREAMING, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
    clientCall.request(10);
    clientCall.sendMessage(
        new ByteArrayInputStream("Trigger EOS".getBytes(StandardCharsets.UTF_8)));

    // We need to wait until the sidecar processes the message and signals endOfStream.
    // When endOfStream is received by data plane, it calls delegate.onHalfClose()
    assertThat(dataPlaneHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneMessages.get()).isEqualTo(1);
    assertThat(sidecarMessages.get()).isEqualTo(1);

    // Now send another message. It should be discarded!
    clientCall.sendMessage(new ByteArrayInputStream("Too late".getBytes(StandardCharsets.UTF_8)));

    // Wait a little bit to make sure it is processed and discarded
    Thread.sleep(100);

    assertThat(dataPlaneMessages.get()).isEqualTo(1);
    assertThat(sidecarMessages.get()).isEqualTo(1);

    clientCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void givenRequestBodyModeNone_whenSendMessageCalled_thenMessageSentDirectlyToDataPlane()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicInteger extProcBodyCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  extProcBodyCount.incrementAndGet();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            try {
              byte[] bytes = ByteStreams.toByteArray(request);
              receivedBody.set(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedBody.set("Error reading: " + e.getMessage());
            }
            responseObserver.onNext(
                new ByteArrayInputStream("Hello Back".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            dataPlaneLatch.countDown();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Original".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBody.get()).isEqualTo("Original");
    assertThat(extProcBodyCount.get()).isEqualTo(0);

    clientCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // ============================================================================
  // Category 8: Response Header Mutation
  // ============================================================================

  @Test
  public void serverInterceptor_responseHeaderMutation_mutatesHeader() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey(
                                                                      "x-mutated-response-header")
                                                                  .setValue(
                                                                      "mutated-response-value")
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    ServerInterceptor headersInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void sendHeaders(Metadata responseHeaders) {
                    responseHeaders.put(
                        Metadata.Key.of(
                            "x-initial-response-header", Metadata.ASCII_STRING_MARSHALLER),
                        "initial-response-value");
                    super.sendHeaders(responseHeaders);
                  }
                },
                headers);
          }
        };

    startDataPlane(interceptor, headersInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedResponseHeaders = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeaders.set(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedResponseHeaders.get()).isNotNull();
    assertThat(
            receivedResponseHeaders
                .get()
                .get(
                    Metadata.Key.of("x-mutated-response-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated-response-value");
    assertThat(
            receivedResponseHeaders
                .get()
                .get(
                    Metadata.Key.of("x-initial-response-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("initial-response-value");
  }

  @Test
  public void givenResponseHeaderModeSkip_responseHeadersSentDirectlyDownstream() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    Metadata.Key<String> customKey =
        Metadata.Key.of("custom-response-header", Metadata.ASCII_STRING_MARSHALLER);

    final java.util.concurrent.atomic.AtomicBoolean responseHeadersReceived =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    final CountDownLatch requestHeadersLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                ProcessingResponse.Builder response = ProcessingResponse.newBuilder();
                if (request.hasRequestHeaders()) {
                  response.setRequestHeaders(
                      HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder().build())
                          .build());
                  requestHeadersLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseHeadersReceived.set(true);
                }
                responseObserver.onNext(response.build());
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    ServerInterceptor headersInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void sendHeaders(Metadata responseHeaders) {
                    responseHeaders.put(customKey, "custom-value");
                    super.sendHeaders(responseHeaders);
                  }
                },
                headers);
          }
        };

    startDataPlane(interceptor, headersInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedResponseHeaders = new AtomicReference<>();
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeaders.set(headers);
            headersLatch.countDown();
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(requestHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(receivedResponseHeaders.get()).isNotNull();
    assertThat(receivedResponseHeaders.get().get(customKey)).isEqualTo("custom-value");

    Thread.sleep(500);
    assertThat(responseHeadersReceived.get()).isFalse();

    channelManager.close();
  }

  // ============================================================================
  // Category 9: Body Mutation: Outbound/Response (GRPC Mode)
  // ============================================================================

  @Test
  public void serverInterceptor_responseHeaderModeSkip_doesNotSendResponseHeaders()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1); // 1 for request headers
    final AtomicBoolean responseHeadersReceived = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseHeadersReceived.set(true);
                } else if (request.hasResponseBody()) {
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    ServerInterceptor headersInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void sendHeaders(Metadata responseHeaders) {
                    responseHeaders.put(
                        Metadata.Key.of(
                            "x-initial-response-header", Metadata.ASCII_STRING_MARSHALLER),
                        "initial-response-value");
                    super.sendHeaders(responseHeaders);
                  }
                },
                headers);
          }
        };

    startDataPlane(interceptor, headersInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedResponseHeaders = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeaders.set(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseHeadersReceived.get()).isFalse();
    assertThat(receivedResponseHeaders.get()).isNotNull();
    assertThat(
            receivedResponseHeaders
                .get()
                .get(
                    Metadata.Key.of("x-initial-response-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("initial-response-value");
  }

  @Test
  public void serverInterceptor_responseBodyModeGrpc_whenOnMessageCalled_thenMessageSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarBodyLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  if (capturedRequest.get() == null
                      && !request.getResponseBody().getBody().isEmpty()) {
                    capturedRequest.set(request);
                    sidecarBodyLatch.countDown();
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(
                                                              request.getResponseBody().getBody())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("Server Message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final AtomicReference<String> receivedMessage = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              receivedMessage.set(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedMessage.set("Error: " + e.getMessage());
            }
            appMessageLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(
            new String(
                capturedRequest.get().getResponseBody().getBody().toByteArray(),
                StandardCharsets.UTF_8))
        .isEqualTo("Server Message");
    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedMessage.get()).isEqualTo("Server Message");
  }

  @Test
  public void serverInterceptor_responseHeadersAndBodyModeGrpc_succeeds() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> capturedRequests =
        java.util.Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(
                                                              request.getResponseBody().getBody())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
                sidecarFinishedLatch.countDown();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("Server Message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch clientCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final AtomicReference<String> receivedMessage = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              receivedMessage.set(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedMessage.set("Error: " + e.getMessage());
            }
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            clientCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(clientCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();
    assertThat(receivedMessage.get()).isEqualTo("Server Message");

    assertThat(sidecarFinishedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    int responseHeadersCount = 0;
    int responseBodyCount = 0;
    int responseTrailersCount = 0;
    for (ProcessingRequest request : capturedRequests) {
      if (request.hasResponseHeaders()) {
        responseHeadersCount++;
      } else if (request.hasResponseBody()) {
        responseBodyCount++;
      } else if (request.hasResponseTrailers()) {
        responseTrailersCount++;
      }
    }
    assertThat(responseHeadersCount).isEqualTo(1);
    assertThat(responseBodyCount).isEqualTo(1);
    assertThat(responseTrailersCount).isEqualTo(1);
  }

  @Test
  public void
      serverInterceptor_respBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedDelivered()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getResponseBody().getBody().isEmpty()
                      && (request.getResponseBody().getEndOfStream()
                          || request.getResponseBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder().build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(
                                                ByteString.copyFromUtf8("Mutated Server Message"))
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setResponseBody(bodyResponse).build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream(
                    "Original Server Message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final AtomicReference<String> receivedMessage = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              receivedMessage.set(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedMessage.set("Error: " + e.getMessage());
            }
            appMessageLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedMessage.get()).isEqualTo("Mutated Server Message");
  }

  @Test
  public void
      serverInterceptor_responseBodyModeGrpc_whenExtProcRespondsEmpty_thenEmptyMsgDelivered()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(ByteString.EMPTY)
                                                          .setEndOfStream(
                                                              request
                                                                  .getResponseBody()
                                                                  .getEndOfStream())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("Server Message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final AtomicReference<String> receivedMessage = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              receivedMessage.set(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedMessage.set("Error: " + e.getMessage());
            }
            appMessageLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedMessage.get()).isEqualTo("");
  }

  @Test
  public void
      serverInterceptor_respBodyModeNone_whenServerSendsMessage_thenMessageSentDirectlyToClient()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicInteger extProcBodyCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  extProcBodyCount.incrementAndGet();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("Server Message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final AtomicReference<String> receivedMessage = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              receivedMessage.set(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (Exception e) {
              receivedMessage.set("Error: " + e.getMessage());
            }
            appMessageLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedMessage.get()).isEqualTo("Server Message");
    assertThat(extProcBodyCount.get()).isEqualTo(0);
  }

  // ============================================================================
  // Category 10: Response Trailers
  // ============================================================================

  @Test
  public void
      givenResponseTrailerModeSend_whenCallCloses_thenResponseTrailersAndStatusPropagatedToClient()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response trailers
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              TrailersResponse.newBuilder()
                                  .setHeaderMutation(
                                      HeaderMutation.newBuilder()
                                          .addSetHeaders(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                                  .newBuilder()
                                                  .setHeader(
                                                      io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                          .newBuilder()
                                                          .setKey("x-mutated-trailer")
                                                          .setValue("mutated-trailer-value")
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedTrailers = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedTrailers.set(trailers);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedTrailers.get()).isNotNull();
    assertThat(
            receivedTrailers
                .get()
                .get(Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated-trailer-value");
  }

  @Test
  public void givenResponseTrailerModeSend_whenCallCloses_thenResponseTrailersSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response trailers
    final AtomicReference<ProcessingRequest> capturedTrailerRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  capturedTrailerRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest req = capturedTrailerRequest.get();
    assertThat(req).isNotNull();
    assertThat(req.hasResponseTrailers()).isTrue();
  }

  @Test
  public void givenResponseTrailerModeDefault_whenCallCloses_thenResponseTrailersNotSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.DEFAULT)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    final AtomicBoolean responseTrailersReceived = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseTrailersReceived.set(true);
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseTrailersReceived.get()).isFalse();
  }

  @Test
  public void givenResponseTrailerModeSkip_whenCallCloses_thenResponseTrailersNotSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    final AtomicBoolean responseTrailersReceived = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseTrailersReceived.set(true);
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseTrailersReceived.get()).isFalse();
  }

  // ============================================================================
  // Category 11: Trailers-Only Response Handling
  // ============================================================================

  @Test
  public void givenResponseHeaderModeSend_whenTrailersOnlySent_thenResponseHeadersSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("x-mutated-trailer")
                                                                  .setValue("mutated-trailer-value")
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            // Directly close with error without calling onNext / sendHeaders
            responseObserver.onError(
                Status.UNAUTHENTICATED
                    .withDescription("forced-trailers-only")
                    .asRuntimeException());
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedTrailers = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            receivedTrailers.set(trailers);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(receivedTrailers.get()).isNotNull();
    assertThat(
            receivedTrailers
                .get()
                .get(Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated-trailer-value");
  }

  @Test
  public void givenResponseHeaderModeDefault_whenTrailersOnlySent_thenResponseHeadersSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("x-mutated-trailer")
                                                                  .setValue("mutated-trailer-value")
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            // Directly close with error without calling onNext / sendHeaders
            responseObserver.onError(
                Status.UNAUTHENTICATED
                    .withDescription("forced-trailers-only")
                    .asRuntimeException());
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedTrailers = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            receivedTrailers.set(trailers);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(receivedTrailers.get()).isNotNull();
    assertThat(
            receivedTrailers
                .get()
                .get(Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated-trailer-value");
  }

  @Test
  public void givenResponseHeaderModeSkip_whenTrailersOnlySent_thenResponseHeadersNotSentToExtProc()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(1); // 1 for request headers, response headers/trailers skipped
    final AtomicBoolean responseHeadersReceived = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseHeadersReceived.set(true);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            Metadata trailers = new Metadata();
            trailers.put(
                Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER),
                "original");
            responseObserver.onError(
                Status.UNAUTHENTICATED
                    .withDescription("forced-trailers-only")
                    .asRuntimeException(trailers));
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedTrailers = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            receivedTrailers.set(trailers);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseHeadersReceived.get()).isFalse();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(receivedStatus.get().getDescription()).isEqualTo("forced-trailers-only");
    assertThat(receivedTrailers.get()).isNotNull();
    assertThat(
            receivedTrailers
                .get()
                .get(Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("original");
  }

  // ============================================================================
  // Category 12: Half-Close handling
  // ============================================================================

  @Test
  public void givenRequestBodyModeGrpc_whenHalfCloseCalled_extProcCalledWithEos() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2); // 1 for headers, 1 for body EOS
    final AtomicBoolean receivedEos = new AtomicBoolean(false);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    receivedEos.set(true);
                    extProcLatch.countDown();
                  } else {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(CommonResponse.newBuilder().build())
                                    .build())
                            .build());
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    try {
      clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
      clientCall.request(1);
      clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
      clientCall.halfClose();

      assertWithMessage("Ext proc should receive headers and body EOS")
          .that(extProcLatch.await(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(receivedEos.get()).isTrue();
      assertThat(appHalfCloseLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();
      assertThat(serverReceivedMessages).isEmpty();
    } finally {
      clientCall.cancel("Cleanup", null);
      channelManager.close();
    }
  }

  @Test
  public void
      deferredHalfClose_whenExtProcRespondsWithEosWithoutMessage_thenAppListenerReceivesHalfClose()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setEndOfStreamWithoutMessage(true)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    extProcLatch.countDown();
                  } else {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                request.getRequestBody().getBody())
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    try {
      clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
      clientCall.request(1);
      clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
      clientCall.halfClose();

      assertWithMessage("Ext proc should receive headers and body EOS")
          .that(extProcLatch.await(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverReceivedMessages).containsExactly("hello");
    } finally {
      clientCall.cancel("Cleanup", null);
      channelManager.close();
    }
  }

  @Test
  public void
      givenDeferredHalfClose_whenExtProcRespondsWithEndOfStream_thenAppListenerReceivesHalfClose()
          throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                ByteString.copyFromUtf8(
                                                                    " mutated-eof"))
                                                            .setEndOfStream(true)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    extProcLatch.countDown();
                  } else {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                request.getRequestBody().getBody())
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    try {
      clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
      clientCall.request(1);
      clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
      clientCall.halfClose();

      assertWithMessage("Ext proc should receive headers and body EOS")
          .that(extProcLatch.await(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverReceivedMessages).containsExactly("hello", " mutated-eof");
    } finally {
      clientCall.cancel("Cleanup", null);
      channelManager.close();
    }
  }

  @Test
  public void extProcEosNoMsg_whenClientNotHalfClosed_thenAppHalfClosed_moreMessagesDiscarded()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ByteString> extProcReceivedBodies =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  extProcReceivedBodies.add(request.getRequestBody().getBody());
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setEndOfStreamWithoutMessage(true)
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    try {
      clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
      clientCall.request(1);
      clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

      assertWithMessage("Ext proc should receive request headers and body")
          .that(extProcLatch.await(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Client sends another message after half-close has been propagated to app listener
      clientCall.sendMessage(
          new ByteArrayInputStream("extra-message".getBytes(StandardCharsets.UTF_8)));

      // Wait a short time to ensure it is discarded
      Thread.sleep(200);

      // Verify app listener received no messages (discarded due to EOS without message) and not
      // "extra-message"
      assertThat(serverReceivedMessages).isEmpty();
      // Verify ext-proc did not receive "extra-message" either
      assertThat(extProcReceivedBodies).containsExactly(ByteString.copyFromUtf8("hello"));

      clientCall.halfClose();
    } finally {
      clientCall.cancel("Cleanup", null);
      channelManager.close();
    }
  }

  @Test
  public void extProcEos_whenClientNotHalfClosed_thenAppHalfClosed_moreMessagesDiscarded()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ByteString> extProcReceivedBodies =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  extProcReceivedBodies.add(request.getRequestBody().getBody());
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(
                                                              ByteString.copyFromUtf8(
                                                                  "hello mutated"))
                                                          .setEndOfStream(true)
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    try {
      clientCall.start(new io.grpc.ClientCall.Listener<InputStream>() {}, new Metadata());
      clientCall.request(1);
      clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

      assertWithMessage("Ext proc should receive request headers and body")
          .that(extProcLatch.await(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverReceivedMessages).containsExactly("hello mutated");

      // Client sends another message after half-close has been propagated to app listener
      clientCall.sendMessage(
          new ByteArrayInputStream("extra-message".getBytes(StandardCharsets.UTF_8)));

      // Wait a short time to ensure it is discarded
      Thread.sleep(200);

      // Verify app listener only received "hello mutated" and not "extra-message"
      assertThat(serverReceivedMessages).containsExactly("hello mutated");
      // Verify ext-proc did not receive "extra-message" either
      assertThat(extProcReceivedBodies).containsExactly(ByteString.copyFromUtf8("hello"));

      clientCall.halfClose();
    } finally {
      clientCall.cancel("Cleanup", null);
      channelManager.close();
    }
  }

  @Test
  public void givenCallFailed_whenClientHalfClosesAndAppCompletes_thenErrorStatusPreserved() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1); // Resp Headers (trailers-only)

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        // Fail the call immediately
        responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());

        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            // Buggy app attempts to call onCompleted after onError
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> callStatus = new AtomicReference<>();
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {}

      @Override
      public void onClose(Status status, Metadata trailers) {
        callStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());

    // Client half-closes to trigger onCompleted on server
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Assert that the status code is NOT overwritten to OK
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

    channelManager.close();
  }

  @Test
  public void serverInterceptor_deferredHalfClose_idle() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());
    clientCall.request(1);
    clientCall.halfClose();

    // Verify ext_proc received headers
    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Verify call is not active yet (waiting for headers response)
    // and half-close is deferred
    assertThat(appHalfCloseLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();

    // Respond to headers to activate call
    extProcResponseObserverRef.get().onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    // Verify half-close is now propagated
    assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify call completes successfully
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();
  }

  // ============================================================================
  // Category 13: Outbound Backpressure (isReady / onReady)
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityTrue_whenExtProcBusy_thenIsReadyReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                                    ReqT, RespT>(next.newCall(method, callOptions)) {
                                  @Override
                                  public boolean isReady() {
                                    return sidecarReady.get();
                                  }
                                };
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    final AtomicBoolean rawCallReady = new AtomicBoolean(true);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return rawCallReady.get();
          }
        };

    try {
      interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      // Wait for activation (ext_proc response received)
      long startTime = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Ext proc busy
      sidecarReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Ext proc ready again
      sidecarReady.set(true);
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Raw call not ready
      rawCallReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isFalse();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityMode_whenClientBusy_thenIsReadyReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean clientReady = new AtomicBoolean(true);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return clientReady.get();
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    try {
      interceptor.interceptCall(
          rawCall,
          new Metadata(),
          (call, headers) -> {
            interceptedCallRef.set(call);
            return new ServerCall.Listener<InputStream>() {};
          });

      // Wait for activation (sidecar needs to respond to headers)
      long startTime = System.currentTimeMillis();
      while (interceptedCallRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get()).isNotNull();
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Client becomes busy
      clientReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isFalse();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenNormalMode_whenClientBusy_thenIsReadyReturnsTrue() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(false)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean clientReady = new AtomicBoolean(false);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return clientReady.get();
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    try {
      interceptor.interceptCall(
          rawCall,
          new Metadata(),
          (call, headers) -> {
            interceptedCallRef.set(call);
            return new ServerCall.Listener<InputStream>() {};
          });

      // Wait for activation (sidecar needs to respond to headers)
      long startTime = System.currentTimeMillis();
      while (interceptedCallRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get()).isNotNull();

      // Since sidecar is ready, interceptedCallRef.get().isReady() should return true,
      // ignoring that client (rawCall) is busy
      assertThat(interceptedCallRef.get().isReady()).isTrue();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenCongestionInExtProc_whenExtProcBecomesReady_thenTriggersOnReady()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                                    ReqT, RespT>(next.newCall(method, callOptions)) {
                                  @Override
                                  public void start(
                                      Listener<RespT> responseListener, Metadata headers) {
                                    sidecarListenerRef.set(
                                        (Listener<ProcessingResponse>) responseListener);
                                    super.start(responseListener, headers);
                                  }
                                };
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch onReadyLatch = new CountDownLatch(1);
    ServerCall.Listener<InputStream> appListener =
        new ServerCall.Listener<InputStream>() {
          @Override
          public void onReady() {
            onReadyLatch.countDown();
          }
        };

    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          return appListener;
        };

    try {
      interceptor.interceptCall(
          new SimpleServerCall(METHOD_SAY_HELLO_RAW), new Metadata(), nextHandler);

      // Wait for sidecar call to start and listener to be captured
      long startTime = System.currentTimeMillis();
      while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(sidecarListenerRef.get()).isNotNull();

      // Trigger sidecar onReady
      sidecarListenerRef.get().onReady();

      // Verify app listener notified
      assertThat(onReadyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  // ============================================================================
  // Category 14: Ext-proc request draining
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenIsReadyCalled_thenReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch drainLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestDrain(true).build());
                  drainLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    try {
      interceptor.interceptCall(
          new SimpleServerCall(METHOD_SAY_HELLO_RAW), new Metadata(), nextHandler);

      assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // isReady() must return false during drain.
      long start = System.currentTimeMillis();
      while (interceptedCallRef.get().isReady() && System.currentTimeMillis() - start < 2000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isFalse();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenOnReady() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch sidecarOnNextLatch = new CountDownLatch(1);
    final CountDownLatch sidecarOnCompletedLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  new Thread(
                          () -> {
                            responseObserver.onNext(
                                ProcessingResponse.newBuilder().setRequestDrain(true).build());
                            sidecarOnNextLatch.countDown();
                            try {
                              if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                sidecarOnCompletedLatch.countDown();
                                responseObserver.onCompleted();
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          })
                      .start();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch onReadyLatch = new CountDownLatch(1);
    ServerCall.Listener<InputStream> appListener =
        new ServerCall.Listener<InputStream>() {
          @Override
          public void onReady() {
            onReadyLatch.countDown();
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      interceptor.interceptCall(
          rawCall,
          new Metadata(),
          (call, headers) -> {
            interceptedCallRef.set(call);
            return appListener;
          });

      // Wait for sidecar to send drain and test to observe it
      assertThat(sidecarOnNextLatch.await(5, TimeUnit.SECONDS)).isTrue();

      long start = System.currentTimeMillis();
      while (interceptedCallRef.get() == null && System.currentTimeMillis() - start < 2000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get()).isNotNull();
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Now let sidecar complete
      sidecarFinishLatch.countDown();

      assertThat(sidecarOnCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // After sidecar stream completes, it should trigger onReady and become ready
      assertThat(onReadyLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(interceptedCallRef.get().isReady()).isTrue();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenMessagesProceed()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  new Thread(
                          () -> {
                            responseObserver.onNext(
                                ProcessingResponse.newBuilder().setRequestDrain(true).build());
                            try {
                              if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                responseObserver.onCompleted();
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          })
                      .start();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    ServerCall.Listener<InputStream> appListener =
        new ServerCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              serverReceivedMessages.add(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
              appMessageLatch.countDown();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    final List<InputStream> rawSentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch rawSentLatch = new CountDownLatch(1);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void sendMessage(InputStream message) {
            rawSentMessages.add(message);
            rawSentLatch.countDown();
          }

          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      ServerCall.Listener<InputStream> interceptedListener =
          interceptor.interceptCall(
              rawCall,
              new Metadata(),
              (call, headers) -> {
                interceptedCallRef.set(call);
                return appListener;
              });

      // Wait for drain to be processed
      long start = System.currentTimeMillis();
      while ((interceptedCallRef.get() == null || interceptedCallRef.get().isReady())
          && System.currentTimeMillis() - start < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Now let sidecar complete
      sidecarFinishLatch.countDown();

      // Wait for it to become ready again
      start = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - start < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // 1. Verify client message is delivered to app listener without sidecar contact
      interceptedListener.onMessage(
          new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
      assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverReceivedMessages).containsExactly("hello");

      // 2. Verify server app response message is sent to client without sidecar contact
      interceptedCallRef
          .get()
          .sendMessage(new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
      assertThat(rawSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              new String(ByteStreams.toByteArray(rawSentMessages.get(0)), StandardCharsets.UTF_8))
          .isEqualTo("response");
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void
      drainingStartsBeforeResponseHeaders_whenAppSendsMessagesAndStatus_thenBufferedAndDelivered()
          throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final AtomicInteger extProcReceivedBodyCount = new AtomicInteger(0);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  new Thread(
                          () -> {
                            responseObserver.onNext(
                                ProcessingResponse.newBuilder().setRequestDrain(true).build());
                            try {
                              if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                responseObserver.onCompleted();
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          })
                      .start();
                } else if (request.hasResponseBody()) {
                  extProcReceivedBodyCount.incrementAndGet();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                drainCompletedLatch.countDown();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    final List<Metadata> rawSentHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<InputStream> rawSentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicReference<Status> rawSentStatus = new AtomicReference<>();
    final AtomicReference<Metadata> rawSentTrailers = new AtomicReference<>();
    final CountDownLatch rawCloseLatch = new CountDownLatch(1);

    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void sendHeaders(Metadata headers) {
            rawSentHeaders.add(headers);
          }

          @Override
          public void sendMessage(InputStream message) {
            rawSentMessages.add(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            rawSentStatus.set(status);
            rawSentTrailers.set(trailers);
            rawCloseLatch.countDown();
          }

          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      interceptor.interceptCall(
          rawCall,
          new Metadata(),
          (call, headers) -> {
            interceptedCallRef.set(call);
            return new ServerCall.Listener<InputStream>() {};
          });

      // Wait for drain to be processed
      assertThat(drainCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // App sends response headers, message and closes server-side concurrently during drain
      final CountDownLatch appActionLatch = new CountDownLatch(1);
      new Thread(
              () -> {
                ServerCall<InputStream, InputStream> interceptedCall = interceptedCallRef.get();
                interceptedCall.sendHeaders(new Metadata());
                interceptedCall.sendMessage(
                    new ByteArrayInputStream(
                        "response message during drain".getBytes(StandardCharsets.UTF_8)));
                interceptedCall.close(Status.OK, new Metadata());
                appActionLatch.countDown();
              })
          .start();

      assertThat(appActionLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Assert that it was NOT received by extProc
      assertThat(extProcReceivedBodyCount.get()).isEqualTo(0);
      // Assert that nothing has been delivered to client (rawCall) yet because drain is active
      assertThat(rawSentHeaders).isEmpty();
      assertThat(rawSentMessages).isEmpty();
      assertThat(rawSentStatus.get()).isNull();

      // Now let sidecar complete the drain
      sidecarFinishLatch.countDown();

      // Wait for rawCall to close
      assertThat(rawCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Verify delivery order: headers first, then app response message during drain, then close
      assertThat(rawSentHeaders).hasSize(1);

      List<String> deliveredMessages = new ArrayList<>();
      for (InputStream is : rawSentMessages) {
        deliveredMessages.add(new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8));
      }
      assertThat(deliveredMessages).containsExactly("response message during drain").inOrder();

      assertThat(rawSentStatus.get().isOk()).isTrue();
      assertThat(rawSentTrailers.get()).isNotNull();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void
      drainingStartsAfterResponseHeaders_whenAppSendsMessagesAndStatus_thenBufferedAndDelivered()
          throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final AtomicInteger extProcReceivedBodyCount = new AtomicInteger(0);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasResponseBody()) {
                  int bodyIdx = extProcReceivedBodyCount.incrementAndGet();
                  if (bodyIdx == 1) {
                    // Send mutated response body for the first original message
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setResponseBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                ByteString.copyFromUtf8(
                                                                    "mutated-msg-1"))
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else if (bodyIdx == 2) {
                    // Send mutated response body for second original message and trigger draining
                    new Thread(
                            () -> {
                              responseObserver.onNext(
                                  ProcessingResponse.newBuilder()
                                      .setResponseBody(
                                          BodyResponse.newBuilder()
                                              .setResponse(
                                                  CommonResponse.newBuilder()
                                                      .setBodyMutation(
                                                          BodyMutation.newBuilder()
                                                              .setStreamedResponse(
                                                                  StreamedBodyResponse.newBuilder()
                                                                      .setBody(
                                                                          ByteString.copyFromUtf8(
                                                                              "mutated-msg-2"))
                                                                      .build())
                                                              .build())
                                                      .build())
                                              .build())
                                      .setRequestDrain(true)
                                      .build());
                              try {
                                if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                  responseObserver.onCompleted();
                                }
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            })
                        .start();
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                drainCompletedLatch.countDown();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    final List<Metadata> rawSentHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<InputStream> rawSentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicReference<Status> rawSentStatus = new AtomicReference<>();
    final AtomicReference<Metadata> rawSentTrailers = new AtomicReference<>();
    final CountDownLatch rawCloseLatch = new CountDownLatch(1);

    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void sendHeaders(Metadata headers) {
            rawSentHeaders.add(headers);
          }

          @Override
          public void sendMessage(InputStream message) {
            rawSentMessages.add(message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            rawSentStatus.set(status);
            rawSentTrailers.set(trailers);
            rawCloseLatch.countDown();
          }

          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      interceptor.interceptCall(
          rawCall,
          new Metadata(),
          (call, headers) -> {
            interceptedCallRef.set(call);
            return new ServerCall.Listener<InputStream>() {};
          });

      ServerCall<InputStream, InputStream> interceptedCall = interceptedCallRef.get();

      // App sends response headers
      interceptedCall.sendHeaders(new Metadata());
      // Wait for headers to be received by client
      long startTime = System.currentTimeMillis();
      while (rawSentHeaders.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(rawSentHeaders).hasSize(1);

      // App sends 1st message
      interceptedCall.sendMessage(
          new ByteArrayInputStream("original-msg-1".getBytes(StandardCharsets.UTF_8)));
      // Wait for 1st mutated message to be received by client
      startTime = System.currentTimeMillis();
      while (rawSentMessages.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(rawSentMessages).hasSize(1);

      // App sends 2nd message
      interceptedCall.sendMessage(
          new ByteArrayInputStream("original-msg-2".getBytes(StandardCharsets.UTF_8)));

      // Wait for 2nd mutated message to be received by client, and wait for drain to be active
      startTime = System.currentTimeMillis();
      while ((rawSentMessages.size() < 2 || interceptedCall.isReady())
          && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(rawSentMessages).hasSize(2);
      assertThat(interceptedCall.isReady()).isFalse();

      // Now that draining is active, App sends message and closes the server side of the call
      // concurrently
      final CountDownLatch appActionLatch = new CountDownLatch(1);
      new Thread(
              () -> {
                interceptedCall.sendMessage(
                    new ByteArrayInputStream(
                        "unmutated-msg-during-drain".getBytes(StandardCharsets.UTF_8)));
                interceptedCall.close(Status.OK, new Metadata());
                appActionLatch.countDown();
              })
          .start();

      assertThat(appActionLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Assert that nothing has been delivered to client (rawCall) during active drain
      assertThat(rawSentMessages).hasSize(2); // Still only 2 mutated messages
      assertThat(rawSentStatus.get()).isNull();

      // Now let sidecar complete the drain
      sidecarFinishLatch.countDown();

      // Wait for rawCall to close
      assertThat(rawCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Verify delivery order: headers, mutated-msg-1, mutated-msg-2, unmutated-msg-during-drain,
      // and status OK
      assertThat(rawSentHeaders).hasSize(1);

      List<String> deliveredMessages = new ArrayList<>();
      for (InputStream is : rawSentMessages) {
        deliveredMessages.add(new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8));
      }
      assertThat(deliveredMessages)
          .containsExactly("mutated-msg-1", "mutated-msg-2", "unmutated-msg-during-drain")
          .inOrder();

      assertThat(rawSentStatus.get().isOk()).isTrue();
      assertThat(rawSentTrailers.get()).isNotNull();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void drainingStartsBeforeRequestHeaders_whenClientSendsMessages_thenBufferedAndDelivered()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  new Thread(
                          () -> {
                            responseObserver.onNext(
                                ProcessingResponse.newBuilder().setRequestDrain(true).build());
                            try {
                              if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                responseObserver.onCompleted();
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          })
                      .start();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                drainCompletedLatch.countDown();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    ServerCall.Listener<InputStream> appListener =
        new ServerCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              serverReceivedMessages.add(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
              appMessageLatch.countDown();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      ServerCall.Listener<InputStream> interceptedListener =
          interceptor.interceptCall(
              rawCall,
              new Metadata(),
              (call, headers) -> {
                interceptedCallRef.set(call);
                return appListener;
              });

      // Wait for drain to be processed
      assertThat(drainCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Client sends request message during drain state
      interceptedListener.onMessage(
          new ByteArrayInputStream("client message during drain".getBytes(StandardCharsets.UTF_8)));

      // Verify app listener has NOT received the message yet because the drain is active
      assertThat(serverReceivedMessages).isEmpty();

      // Now let sidecar complete
      sidecarFinishLatch.countDown();

      // Wait for it to become ready again
      long startTime = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Verify that the buffered client request message is now delivered to the app
      assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverReceivedMessages).containsExactly("client message during drain");
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void drainingStartsAfterRequestHeaders_whenClientSendsMessages_thenBufferedAndDelivered()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final AtomicInteger extProcReceivedBodyCount = new AtomicInteger(0);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  int bodyIdx = extProcReceivedBodyCount.incrementAndGet();
                  if (bodyIdx == 1) {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                ByteString.copyFromUtf8(
                                                                    "mutated-msg-1"))
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else if (bodyIdx == 2) {
                    new Thread(
                            () -> {
                              responseObserver.onNext(
                                  ProcessingResponse.newBuilder()
                                      .setRequestBody(
                                          BodyResponse.newBuilder()
                                              .setResponse(
                                                  CommonResponse.newBuilder()
                                                      .setBodyMutation(
                                                          BodyMutation.newBuilder()
                                                              .setStreamedResponse(
                                                                  StreamedBodyResponse.newBuilder()
                                                                      .setBody(
                                                                          ByteString.copyFromUtf8(
                                                                              "mutated-msg-2"))
                                                                      .build())
                                                              .build())
                                                      .build())
                                              .build())
                                      .setRequestDrain(true)
                                      .build());
                              try {
                                if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                                  responseObserver.onCompleted();
                                }
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            })
                        .start();
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                drainCompletedLatch.countDown();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    ServerCall.Listener<InputStream> appListener =
        new ServerCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              serverReceivedMessages.add(
                  new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onHalfClose() {
            appHalfCloseLatch.countDown();
          }
        };

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return true;
          }
        };

    try {
      ServerCall.Listener<InputStream> interceptedListener =
          interceptor.interceptCall(
              rawCall,
              new Metadata(),
              (call, headers) -> {
                interceptedCallRef.set(call);
                return appListener;
              });

      // Wait until the call is active (headers processed and delegate listener created)
      long startTime = System.currentTimeMillis();
      while (interceptedCallRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get()).isNotNull();

      ServerCall<InputStream, InputStream> interceptedCall = interceptedCallRef.get();

      // Client sends 1st message
      interceptedCall.request(1);
      interceptedListener.onMessage(
          new ByteArrayInputStream("original-msg-1".getBytes(StandardCharsets.UTF_8)));
      // Wait for 1st mutated message to be received by app
      startTime = System.currentTimeMillis();
      while (serverReceivedMessages.size() < 1 && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(serverReceivedMessages).containsExactly("mutated-msg-1");

      // Client sends 2nd message
      interceptedCall.request(1);
      interceptedListener.onMessage(
          new ByteArrayInputStream("original-msg-2".getBytes(StandardCharsets.UTF_8)));
      // Wait for 2nd mutated message to be received by app, and wait for drain to be active
      startTime = System.currentTimeMillis();
      while ((serverReceivedMessages.size() < 2 || interceptedCall.isReady())
          && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(serverReceivedMessages)
          .containsExactly("mutated-msg-1", "mutated-msg-2")
          .inOrder();
      assertThat(interceptedCall.isReady()).isFalse();

      // Client concurrently sends message and half-closes during active drain
      final CountDownLatch clientActionLatch = new CountDownLatch(1);
      new Thread(
              () -> {
                interceptedListener.onMessage(
                    new ByteArrayInputStream(
                        "client-msg-during-drain".getBytes(StandardCharsets.UTF_8)));
                interceptedListener.onHalfClose();
                clientActionLatch.countDown();
              })
          .start();

      assertThat(clientActionLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Assert that nothing has been delivered to the app during active drain
      assertThat(serverReceivedMessages).hasSize(2); // Still only 2 mutated messages
      assertThat(appHalfCloseLatch.getCount()).isEqualTo(1); // Half-close not processed yet

      // Now let sidecar complete the drain
      sidecarFinishLatch.countDown();

      // Wait for the delegate half-close to be processed
      assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Verify delivery order: mutated-msg-1, mutated-msg-2, client-msg-during-drain
      assertThat(serverReceivedMessages)
          .containsExactly("mutated-msg-1", "mutated-msg-2", "client-msg-during-drain")
          .inOrder();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // ignore
        }
      }
      channelManager.close();
    }
  }

  @Test
  public void givenNoRequestDrain_whenExtProcStreamCompletesNormally_thenTreatsAsNonOkAndCallFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setFailureModeAllow(false) // Fail closed
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server completes normally without sending request_drain = true
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  // Complete stream normally without drain
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    channelManager.close();
  }

  // ============================================================================
  // Category 15: Inbound Backpressure (request(n) / pendingRequests)
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityTrue_whenExtProcBusy_thenAppRequestsBuffered() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                                    ReqT, RespT>(next.newCall(method, callOptions)) {
                                  @Override
                                  public void start(
                                      Listener<RespT> responseListener, Metadata headers) {
                                    sidecarListenerRef.set(
                                        (Listener<ProcessingResponse>) responseListener);
                                    super.start(responseListener, headers);
                                  }

                                  @Override
                                  public boolean isReady() {
                                    return sidecarReady.get();
                                  }
                                };
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    final AtomicInteger rawRequestCount = new AtomicInteger(0);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void request(int numMessages) {
            rawRequestCount.addAndGet(numMessages);
          }
        };

    try {
      interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      // Wait for sidecar call to start and listener to be captured
      long startTime = System.currentTimeMillis();
      while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(sidecarListenerRef.get()).isNotNull();

      // Wait for activation
      startTime = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Sidecar is busy
      sidecarReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Application requests more messages
      interceptedCallRef.get().request(5);

      // Verify raw call NOT requested yet
      assertThat(rawRequestCount.get()).isEqualTo(0);

      // Sidecar becomes ready
      sidecarReady.set(true);
      sidecarListenerRef.get().onReady();

      // After sidecar becomes ready, pending requests should be drained to raw call.
      long start = System.currentTimeMillis();
      while (rawRequestCount.get() < 5 && System.currentTimeMillis() - start < 2000) {
        Thread.sleep(10);
      }
      assertThat(rawRequestCount.get()).isEqualTo(5);
      assertThat(interceptedCallRef.get().isReady()).isTrue();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenAppRequestsMessages_thenRequestsBuffered()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch drainLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestDrain(true).build());
                  drainLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    final AtomicInteger rawRequestCount = new AtomicInteger(0);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void request(int numMessages) {
            rawRequestCount.addAndGet(numMessages);
          }
        };

    try {
      interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Wait for interceptedCallRef to become ready first (it will transition to activated, then
      // draining)
      long start = System.currentTimeMillis();
      while (interceptedCallRef.get() == null && System.currentTimeMillis() - start < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get()).isNotNull();

      // isReady() must return false during drain.
      start = System.currentTimeMillis();
      while (interceptedCallRef.get().isReady() && System.currentTimeMillis() - start < 2000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // App requests more messages
      interceptedCallRef.get().request(3);

      // isReady() should remain false during drain
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Verify raw call NOT requested during drain
      assertThat(rawRequestCount.get()).isEqualTo(0);
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  // ============================================================================
  // Category 16: Error Handling & Security
  // ============================================================================

  @Test
  public void serverInterceptor_sendMessage_serializationFailure() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            // Send a corrupt stream that throws IOException on read
            InputStream corruptStream =
                new InputStream() {
                  @Override
                  public int read() throws IOException {
                    throw new IOException("Simulated serialization failure");
                  }
                };
            responseObserver.onNext(corruptStream);
            responseObserver.onCompleted();
          }
        };

    startDummyExtProcServer();
    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("Failed to serialize response body");

    channelManager.close();
  }

  @Test
  public void serverInterceptor_clientRequest_readFailure() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDummyExtProcServer();
    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW_LAZY, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    // Send a corrupt stream that throws IOException on read
    class CorruptStream extends InputStream implements io.grpc.KnownLength {
      @Override
      public int read() throws IOException {
        throw new IOException("Simulated read failure");
      }

      @Override
      public int available() throws IOException {
        return 10;
      }
    }
    InputStream corruptStream = new CorruptStream();

    clientCall.sendMessage(corruptStream);
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getDescription()).contains("Failed to read client request");
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);

    channelManager.close();
  }

  @Test
  public void serverInterceptor_failOpen_allowsCallToProceed() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).setFailureModeAllow(true).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External processor returns immediate error
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserver.onError(
                Status.UNAVAILABLE.withDescription("ExtProc down").asRuntimeException());
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStarted.get()).isTrue();
  }

  @Test
  public void serverInterceptor_failClosed_cancelsCall() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(false) // Fail closed
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserver.onError(
                Status.INTERNAL.withDescription("Simulated sidecar failure").asRuntimeException());
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void givenFailureModeAllowTrue_whenExtProcStreamFailsAfterRequestBodySent_thenCallFails()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  // Fail the stream after receiving the request body
                  responseObserver.onError(
                      Status.INTERNAL
                          .withDescription("Simulated stream failure")
                          .asRuntimeException());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    // Verify stream failed
    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Verify call completed and failed with INTERNAL status (not fail-open)
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStarted.get()).isFalse();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void givenFailureModeAllowTrue_whenExtProcStreamFailsAfterResponseBodySent_thenCallFails()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  // Fail the stream after receiving the response body
                  responseObserver.onError(
                      Status.INTERNAL
                          .withDescription("Simulated stream failure")
                          .asRuntimeException());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    // Verify call completed and failed with INTERNAL status (not fail-open)
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStarted.get()).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void givenObservabilityTrue_whenExtProcStreamFails_thenCallContinues() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).setObservabilityMode(true).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserver.onError(
                Status.UNAVAILABLE.withDescription("ExtProc down").asRuntimeException());
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Verify call completed and succeeded with OK status even though stream failed
    assertThat(callStarted.get()).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.OK);
  }

  @Test
  public void serverInterceptor_compressionNotSupported_requestBody() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  // Respond with compression = true in streamed response
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setGrpcMessageCompressed(true)
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify call failed with UNAVAILABLE
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(closedStatus.get().getDescription()).contains("gRPC message compression not supported in ext_proc");

    channelManager.close();
  }

  @Test
  public void serverInterceptor_compressionNotSupported_responseBody() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  // Respond with compression = true in streamed response
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setGrpcMessageCompressed(true)
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify call failed with UNAVAILABLE
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(closedStatus.get().getDescription()).contains("gRPC message compression not supported in ext_proc");

  }

  @Test
  public void serverInterceptor_observabilityMode_behavior() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setObservabilityMode(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcReqBodyLatch = new CountDownLatch(1);
    final CountDownLatch extProcRespBodyLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("JETS_LOG: ext_proc.onNext: " + request);
                if (request.hasRequestHeaders()) {
                  extProcHeadersLatch.countDown();
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                } else if (request.hasRequestBody()) {
                  extProcReqBodyLatch.countDown();
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setRequestBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setEndOfStreamWithoutMessage(true)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  }
                } else if (request.hasResponseBody()) {
                  extProcRespBodyLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch serverReceivedLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            try {
              serverReceivedMessages.add(new String(ByteStreams.toByteArray(request), StandardCharsets.UTF_8));
              serverReceivedLatch.countDown();
            } catch (IOException e) {
              responseObserver.onError(e);
              return;
            }
            responseObserver.onNext(new ByteArrayInputStream("response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final List<String> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              clientReceivedMessages.add(new String(ByteStreams.toByteArray(message), StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    // Verify ext_proc received headers
    assertThat(extProcHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify server app received message
    assertThat(serverReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly("hello");

    // Verify client received response and close immediately (observability mode)
    clientCall.request(1);
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();
    assertThat(clientReceivedMessages).containsExactly("response");

    // Verify ext_proc received message body and response body asynchronously
    assertThat(extProcReqBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(extProcRespBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_failureModeAllow_failOpen_protocolErrorBeforeBody() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcHeadersLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  extProcHeadersLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    // Wait for ext_proc to receive headers
    assertThat(extProcHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Trigger protocol error by sending out-of-order response (ResponseBodyResponse instead of RequestHeadersResponse)
    extProcResponseObserverRef.get().onNext(
        ProcessingResponse.newBuilder()
            .setResponseHeaders(HeadersResponse.newBuilder().build())
            .build());

    // If fail open works, the call should be activated and app should start.
    // We send a message now.
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    // Verify call completes successfully
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStarted.get()).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_failureModeAllow_failClosed_afterBody() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  extProcHeadersLatch.countDown();
                } else if (request.hasRequestBody()) {
                  extProcBodyLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean callStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            callStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    // Wait for ext_proc to receive headers
    assertThat(extProcHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Respond to headers to activate call
    extProcResponseObserverRef.get().onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    // Send message to trigger request body processing
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

    // Wait for ext_proc to receive body
    assertThat(extProcBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Now trigger stream failure (onError) AFTER body was sent
    extProcResponseObserverRef.get().onError(
        Status.UNAVAILABLE.withDescription("Simulated sidecar failure").asRuntimeException());

    // Verify call is aborted (fails closed)
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void serverInterceptor_clientRequest_bufferFailure_idle() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcHeadersLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  extProcHeadersLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW_LAZY, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    // Send a corrupt stream that throws IOException on read
    class CorruptStream extends InputStream implements io.grpc.KnownLength {
      @Override
      public int read() throws IOException {
        throw new IOException("Simulated read failure");
      }

      @Override
      public int available() throws IOException {
        return 10;
      }
    }
    InputStream corruptStream = new CorruptStream();

    clientCall.sendMessage(corruptStream);
    clientCall.halfClose();

    // Wait for ext_proc to receive headers
    assertThat(extProcHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Respond to headers to activate call.
    // This will trigger rawNext.startCall -> request(2) -> onMessage (synchronously) -> IOException -> close.
    extProcResponseObserverRef.get().onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getDescription()).contains("Failed to buffer client request");
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);

    channelManager.close();
  }

  @Test
  public void serverInterceptor_observabilityMode_responseBody_defaultMode() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setObservabilityMode(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcResponseHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcResponseBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  extProcResponseHeadersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  extProcResponseBodyLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(new ByteArrayInputStream("response_message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(2);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    // Verify call completes
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).hasSize(1);
    byte[] responseBytes = ByteStreams.toByteArray(responses.get(0));
    assertThat(new String(responseBytes, StandardCharsets.UTF_8)).isEqualTo("response_message");

    // In observability mode, headers are sent to ext proc
    assertThat(extProcResponseHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // But response body should NOT be sent to ext proc because body mode is NONE
    assertThat(extProcResponseBodyLatch.await(1, TimeUnit.SECONDS)).isFalse();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_observabilityMode_serverCloseBeforeClientHalfClose() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setObservabilityMode(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(new ByteArrayInputStream("response_message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            return new StreamObserver<InputStream>() {
              @Override public void onNext(InputStream value) {}
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(2);

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).hasSize(1);
    assertThat(closedStatus.get().isOk()).isTrue();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_observabilityMode_earlyClose_noTrailers() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setObservabilityMode(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(new ByteArrayInputStream("response_message".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            return new StreamObserver<InputStream>() {
              @Override public void onNext(InputStream value) {}
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(2);

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).hasSize(1);
    assertThat(closedStatus.get().isOk()).isTrue();
    assertThat(extProcLatch.await(1, TimeUnit.SECONDS)).isFalse();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_trailersOnly_skipHeaders() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
            responseObserver.onCompleted();
            return new StreamObserver<InputStream>() {
              @Override public void onNext(InputStream value) {}
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).isEmpty();
    assertThat(closedStatus.get().isOk()).isTrue();
    assertThat(extProcLatch.await(1, TimeUnit.SECONDS)).isFalse();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_observabilityMode_trailersOnly_skipHeaders() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setObservabilityMode(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                extProcLatch.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
            responseObserver.onCompleted();
            return new StreamObserver<InputStream>() {
              @Override public void onNext(InputStream value) {}
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).isEmpty();
    assertThat(closedStatus.get().isOk()).isTrue();
    assertThat(extProcLatch.await(1, TimeUnit.SECONDS)).isFalse();

    channelManager.close();
  }

  @Test
  public void serverInterceptor_flowControl_closePending_drained() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcFirstBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              int bodyCount = 0;
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcHeadersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  bodyCount++;
                  if (bodyCount == 1) {
                    extProcFirstBodyLatch.countDown();
                  } else if (bodyCount == 2) {
                    responseObserver.onNext(
                        ProcessingResponse.newBuilder()
                            .setResponseBody(
                                BodyResponse.newBuilder()
                                    .setResponse(
                                        CommonResponse.newBuilder()
                                            .setBodyMutation(
                                                BodyMutation.newBuilder()
                                                    .setStreamedResponse(
                                                        StreamedBodyResponse.newBuilder()
                                                            .setBody(
                                                                request.getResponseBody().getBody())
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  }
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final byte[] largeBody = new byte[66 * 1024];
    final byte[] smallBody = new byte[1024];

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(new ByteArrayInputStream(largeBody));
            responseObserver.onNext(new ByteArrayInputStream(smallBody));
            responseObserver.onCompleted();
            return new StreamObserver<InputStream>() {
              @Override public void onNext(InputStream value) {}
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final List<InputStream> responses = new ArrayList<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            responses.add(message);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(10);

    assertThat(extProcHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(extProcFirstBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(callCompletedLatch.await(1, TimeUnit.SECONDS)).isFalse();
    assertThat(responses).isEmpty();

    StreamObserver<ProcessingResponse> extProcResponseObserver = extProcResponseObserverRef.get();
    
    // Respond to Message 1
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setResponseBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(ByteString.copyFrom(largeBody))
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());

    // Send WindowUpdate to allow Msg 2
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setServerWindowUpdate(
                ProcessingResponse.ServerWindowUpdate.newBuilder()
                    .setWindowIncrementUpstreamToSidestream(100 * 1024)
                    .build())
            .build());

    // Now the call should complete because Msg 2 is sent/responded and trailers are sent/responded
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responses).hasSize(2);

    channelManager.close();
  }

  // ============================================================================
  // Category 17: Immediate Response Handling
  // ============================================================================

  @Test
  public void serverInterceptor_immediateResponse_whenReceived_thenDataPlaneCallClosed()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setImmediateResponse(
                              io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse.newBuilder()
                                  .setGrpcStatus(
                                      io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                          .newBuilder()
                                          .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                          .build())
                                  .setDetails("Custom security rejection")
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean dataPlaneStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            dataPlaneStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(closedStatus.get().getDescription()).isEqualTo("Custom security rejection");
    assertThat(dataPlaneStarted.get()).isFalse();
  }

  @Test
  public void serverInterceptor_immediateResponse_duringResponseHeaders_closesCall()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setImmediateResponse(
                              io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse.newBuilder()
                                  .setGrpcStatus(
                                      io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                          .newBuilder()
                                          .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                          .build())
                                  .setDetails("Immediate Auth Failure on Response Headers")
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            // Send a message, which triggers response headers
            responseObserver.onNext(request);
            // Don't complete yet, we expect to be aborted
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final List<String> clientEvents = Collections.synchronizedList(new ArrayList<>());
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            clientEvents.add("HEADERS");
          }

          @Override
          public void onMessage(InputStream message) {
            clientEvents.add("MESSAGE");
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            clientEvents.add("CLOSE:" + status.getCode());
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(closedStatus.get().getDescription()).isEqualTo("Immediate Auth Failure on Response Headers");
    // Client should NOT receive HEADERS or MESSAGE because it was aborted during response headers
    assertThat(clientEvents).containsExactly("CLOSE:UNAUTHENTICATED");
    
    channelManager.close();
  }

  @Test
  public void serverInterceptor_immediateResponseDisabled_whenReceived_thenStreamErrored()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .setDisableImmediateResponse(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setImmediateResponse(
                              io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse.newBuilder()
                                  .setGrpcStatus(
                                      io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                          .newBuilder()
                                          .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                          .build())
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isAnyOf(Status.Code.INTERNAL, Status.Code.UNAVAILABLE);
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void
      serverInterceptor_pendingData_whenImmediateResponseReceived_thenDeliversDataBeforeStatus()
          throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final List<String> clientEvents = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);
    final java.util.concurrent.ExecutorService extProcResponseExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    final Metadata.Key<String> immediateKey =
        Metadata.Key.of("x-immediate-header", Metadata.ASCII_STRING_MARSHALLER);
    final AtomicReference<Metadata> clientTrailers = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                extProcResponseExecutor.submit(
                    () -> {
                      synchronized (responseObserver) {
                        if (request.hasRequestBody()) {
                          try {
                            Thread.sleep(500);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          responseObserver.onNext(
                              ProcessingResponse.newBuilder()
                                  .setImmediateResponse(
                                      io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse
                                          .newBuilder()
                                          .setGrpcStatus(
                                              io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                                  .newBuilder()
                                                  .setStatus(
                                                      Status.UNAUTHENTICATED.getCode().value())
                                                  .build())
                                          .setDetails("Immediate Auth Failure")
                                          .setHeaders(
                                              io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation
                                                  .newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("x-immediate-header")
                                                                  .setValue("true")
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build());
                          responseObserver.onCompleted();
                        }
                      }
                    });
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                extProcCompletedLatch.countDown();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(
              StreamObserver<InputStream> responseObserver) {
            byte[] messageBytes = "server-response".getBytes(StandardCharsets.UTF_8);
            responseObserver.onNext(new ByteArrayInputStream(messageBytes));
            responseObserver.onCompleted();

            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream value) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            clientEvents.add("HEADERS");
          }

          @Override
          public void onMessage(InputStream message) {
            clientEvents.add("MESSAGE");
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            clientEvents.add("CLOSE:" + status.getCode());
            clientTrailers.set(trailers);
            finishLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    byte[] requestBytes = "request-body".getBytes(StandardCharsets.UTF_8);
    clientCall.sendMessage(new ByteArrayInputStream(requestBytes));
    clientCall.halfClose();

    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientEvents).containsExactly("HEADERS", "MESSAGE", "CLOSE:UNAUTHENTICATED");
    assertThat(clientTrailers.get().get(immediateKey)).isEqualTo("true");

    extProcResponseExecutor.shutdown();
    channelManager.close();
  }

  @Test
  public void serverInterceptor_immediateResponse_emptyDetails()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setImmediateResponse(
                              io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse.newBuilder()
                                  .setGrpcStatus(
                                      io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                          .newBuilder()
                                          .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicBoolean dataPlaneStarted = new AtomicBoolean(false);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            dataPlaneStarted.set(true);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(closedStatus.get().getDescription()).isNull();
    assertThat(dataPlaneStarted.get()).isFalse();

    channelManager.close();
  }

  // ============================================================================
  // Category 18: Resource Management
  // ============================================================================

  @Test
  public void givenFilter_whenClosed_thenCachedChannelManagerIsClosed() throws Exception {
    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT, mockChannelManager);
    filter.close();
    Mockito.verify(mockChannelManager).close();
  }

  // ============================================================================
  // Category 19: Data plane rpc cancellation
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenActiveRpc_whenDataPlaneCallCancelled_thenExtProcStreamIsErrored()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch cancelLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {
                cancelLatch.countDown();
              }

              @Override
              public void onCompleted() {}
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall.Listener<InputStream>> interceptedListenerRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          ServerCall.Listener<InputStream> listener = new ServerCall.Listener<InputStream>() {};
          interceptedListenerRef.set(listener);
          return listener;
        };

    ServerCall<InputStream, InputStream> rawCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW);

    try {
      ServerCall.Listener<InputStream> listener =
          interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      // Client cancels the RPC
      listener.onCancel();

      // Verify sidecar control stream also received onError/cancellation
      assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      channelManager.close();
    }
  }

  // ============================================================================
  // Category 20: Flow Control when side stream is full
  // ============================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenIsReadyReturnsFalse()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(false)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                                    ReqT, RespT>(next.newCall(method, callOptions)) {
                                  @Override
                                  public void start(
                                      Listener<RespT> responseListener, Metadata headers) {
                                    sidecarListenerRef.set(
                                        (Listener<ProcessingResponse>) responseListener);
                                    super.start(responseListener, headers);
                                  }

                                  @Override
                                  public boolean isReady() {
                                    return sidecarReady.get();
                                  }
                                };
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    final AtomicBoolean rawCallReady = new AtomicBoolean(true);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public boolean isReady() {
            return rawCallReady.get();
          }
        };

    try {
      interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      // Wait for sidecar call to start and listener to be captured
      long startTime = System.currentTimeMillis();
      while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(sidecarListenerRef.get()).isNotNull();

      // Wait for activation
      startTime = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Sidecar is busy -> intercepted call becomes busy
      sidecarReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isFalse();

      // Sidecar becomes ready, raw call is busy -> intercepted call is STILL ready (observability
      // mode false)
      sidecarReady.set(true);
      rawCallReady.set(false);
      assertThat(interceptedCallRef.get().isReady()).isTrue();
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenAppRequestsAreBuffered()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + uniqueExtProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder()
                                    .setTypeUrl(
                                        "type.googleapis.com/envoy.extensions.grpc_service."
                                            + "channel_credentials.insecure.v3.InsecureCredentials")
                                    .build())
                            .build())
                    .build())
            .setObservabilityMode(false)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                                    ReqT, RespT>(next.newCall(method, callOptions)) {
                                  @Override
                                  public void start(
                                      Listener<RespT> responseListener, Metadata headers) {
                                    sidecarListenerRef.set(
                                        (Listener<ProcessingResponse>) responseListener);
                                    super.start(responseListener, headers);
                                  }

                                  @Override
                                  public boolean isReady() {
                                    return sidecarReady.get();
                                  }
                                };
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef =
        new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler =
        (call, headers) -> {
          interceptedCallRef.set(call);
          return new ServerCall.Listener<InputStream>() {};
        };

    final AtomicInteger rawRequestCount = new AtomicInteger(0);
    ServerCall<InputStream, InputStream> rawCall =
        new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
          @Override
          public void request(int numMessages) {
            rawRequestCount.addAndGet(numMessages);
          }
        };

    try {
      interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      // Wait for sidecar call to start and listener to be captured
      long startTime = System.currentTimeMillis();
      while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(sidecarListenerRef.get()).isNotNull();

      // Wait for activation
      startTime = System.currentTimeMillis();
      while (!interceptedCallRef.get().isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(interceptedCallRef.get().isReady()).isTrue();

      // Sidecar becomes busy -> request(5) should be buffered
      sidecarReady.set(false);
      interceptedCallRef.get().request(5);
      assertThat(rawRequestCount.get()).isEqualTo(0);

      // Sidecar becomes ready -> buffered requests should be drained
      sidecarReady.set(true);
      sidecarListenerRef.get().onReady();

      long start = System.currentTimeMillis();
      while (rawRequestCount.get() < 5 && System.currentTimeMillis() - start < 2000) {
        Thread.sleep(10);
      }
      assertThat(rawRequestCount.get()).isEqualTo(5);
    } finally {
      if (responseObserverRef.get() != null) {
        try {
          responseObserverRef.get().onCompleted();
        } catch (IllegalStateException ignored) {
          // Ignore if already closed
        }
      }
      channelManager.close();
    }
  }

  // ============================================================================
  // Category 21: Streaming Completeness (Client & Bi-Di)
  // ============================================================================

  @Test
  public void serverInterceptor_clientStreaming_streamCompletenessSucceeds() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> capturedRequests =
        java.util.Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getRequestBody().getBody().isEmpty()
                      && (request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(request.getRequestBody().getBody())
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setRequestBody(bodyResponse).build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasResponseBody()) {
                  BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                  if (request.getResponseBody().getBody().isEmpty()
                      && (request.getResponseBody().getEndOfStream()
                          || request.getResponseBody().getEndOfStreamWithoutMessage())) {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder().build())
                                    .build())
                            .build());
                  } else {
                    bodyResponse.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(request.getResponseBody().getBody())
                                            .build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder().setResponseBody(bodyResponse).build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
                sidecarFinishedLatch.countDown();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final List<String> receivedDataPlaneRequests =
        java.util.Collections.synchronizedList(new ArrayList<>());
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloClientStreaming(
              StreamObserver<InputStream> responseObserver) {
            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream value) {
                try {
                  byte[] bytes = ByteStreams.toByteArray(value);
                  receivedDataPlaneRequests.add(new String(bytes, StandardCharsets.UTF_8));
                } catch (IOException e) {
                  responseObserver.onError(e);
                }
              }

              @Override
              public void onError(Throwable t) {
                responseObserver.onError(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onNext(
                    new ByteArrayInputStream("response-payload".getBytes(StandardCharsets.UTF_8)));
                responseObserver.onCompleted();
              }
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_CLIENT_STREAMING, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch clientCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final List<String> clientResponses = java.util.Collections.synchronizedList(new ArrayList<>());

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            try {
              byte[] bytes = ByteStreams.toByteArray(message);
              clientResponses.add(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            clientCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(100);
    clientCall.sendMessage(new ByteArrayInputStream("msg-1".getBytes(StandardCharsets.UTF_8)));
    clientCall.sendMessage(new ByteArrayInputStream("msg-2".getBytes(StandardCharsets.UTF_8)));
    clientCall.sendMessage(new ByteArrayInputStream("msg-3".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(clientCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();

    assertThat(receivedDataPlaneRequests).containsExactly("msg-1", "msg-2", "msg-3").inOrder();
    assertThat(clientResponses).containsExactly("response-payload");

    assertThat(sidecarFinishedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    int requestHeadersCount = 0;
    int requestBodyCount = 0;
    for (ProcessingRequest request : capturedRequests) {
      if (request.hasRequestHeaders()) {
        requestHeadersCount++;
      } else if (request.hasRequestBody()) {
        requestBodyCount++;
      }
    }
    assertThat(requestHeadersCount).isEqualTo(1);
    assertThat(requestBodyCount).isEqualTo(4);
  }

  // ============================================================================
  // Category 22: Header Forwarding Rules
  // ============================================================================

  @Test
  public void serverInterceptor_allowedHeaders_whenHeadersForwarded_thenOnlyAllowedAreSent()
      throws Exception {
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders =
        new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  capturedHeaders.set(request.getRequestHeaders());
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasRequestTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    // Config with forward_rules and explicit processing mode:
    // allowed_headers = ["x-allowed-*", "content-type"], requestHeaderMode = SEND, others = SKIP
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .setForwardRules(
                HeaderForwardingRules.newBuilder()
                    .setAllowedHeaders(
                        io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setPrefix("x-allowed-")
                                    .build())
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setExact("content-type")
                                    .build())
                            .build())
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> callStatus = new AtomicReference<>();
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-allowed-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-disallowed", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(
        Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "application/grpc");

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        headers);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    boolean sidecarAwaited = sidecarLatch.await(5, TimeUnit.SECONDS);
    boolean completedAwaited = callCompletedLatch.await(5, TimeUnit.SECONDS);

    assertThat(sidecarAwaited).isTrue();
    assertThat(completedAwaited).isTrue();
    assertThat(callStatus.get().isOk()).isTrue();

    List<String> headerKeys = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerKeys.add(hv.getKey());
    }

    assertThat(headerKeys).contains("x-allowed-1");
    assertThat(headerKeys).contains("content-type");
    assertThat(headerKeys).doesNotContain("x-disallowed");
  }

  @Test
  public void serverInterceptor_disallowedHeaders_whenHeadersForwarded_thenSkipped()
      throws Exception {
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders =
        new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  capturedHeaders.set(request.getRequestHeaders());
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasRequestTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    // Config with forward_rules and explicit processing mode: requestHeaderMode = SEND, others =
    // SKIP
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .setForwardRules(
                HeaderForwardingRules.newBuilder()
                    .setDisallowedHeaders(
                        io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setExact("x-secret")
                                    .build())
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setExact("authorization")
                                    .build())
                            .build())
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "v3");

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        headers);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    List<String> headerKeys = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerKeys.add(hv.getKey());
    }

    assertThat(headerKeys).contains("x-foo");
    assertThat(headerKeys).doesNotContain("x-secret");
    assertThat(headerKeys).doesNotContain("authorization");
  }

  @Test
  public void serverInterceptor_bothRules_whenHeadersForwarded_thenBothAreApplied()
      throws Exception {
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders =
        new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  capturedHeaders.set(request.getRequestHeaders());
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasRequestTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(
                              io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse.newBuilder()
                                  .build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    // Config with forward_rules and explicit processing mode: requestHeaderMode = SEND, others =
    // SKIP
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .setForwardRules(
                HeaderForwardingRules.newBuilder()
                    .setAllowedHeaders(
                        io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setPrefix("x-foo-")
                                    .build())
                            .build())
                    .setDisallowedHeaders(
                        io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                            .addPatterns(
                                io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                                    .setExact("x-foo-secret")
                                    .build())
                            .build())
                    .build())
            .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-foo-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("x-bar", Metadata.ASCII_STRING_MARSHALLER), "v3");

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        headers);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    List<String> headerKeys = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerKeys.add(hv.getKey());
    }

    assertThat(headerKeys).contains("x-foo-1");
    assertThat(headerKeys).doesNotContain("x-foo-secret");
    assertThat(headerKeys).doesNotContain("x-bar");
  }

  // ============================================================================
  // Category 23: Response Ordering Checks
  // ============================================================================

  @Test
  public void serverInterceptor_protocolError_outOfOrderHeaderResponse() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  // Violate order: send ResponseTrailers response when RequestHeadersResponse is expected
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).isEqualTo("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void serverInterceptor_protocolError_requestBodyBeforeHeaders() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  // Violate order: send RequestBodyResponse when RequestHeadersResponse is expected
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(BodyResponse.newBuilder().build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).isEqualTo("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void serverInterceptor_outOfOrderResponses_whenMessageArrivesBeforeHeaders_thenFails()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                } else if (request.hasResponseHeaders()) {
                  // Violate order: send ResponseBody response when ResponseHeaders response is
                  // expected
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(receivedStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void serverInterceptor_validOrder_whenResponsesArriveInOrder_thenSucceeds()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch =
        new CountDownLatch(2); // 1 for request headers, 1 for response headers
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.OK);
  }

  // ============================================================================
  // Category 24: Header Response Status Checks
  // ============================================================================

  @Test
  public void serverInterceptor_requestHeadersResponse_whenStatusIsContinueAndReplace_thenFails()
      throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setStatus(
                                              CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE)
                                          .build())
                                  .build())
                          .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(receivedStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void serverInterceptor_responseHeadersResponse_whenStatusIsContinueAndReplace_thenFails()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setStatus(
                                              CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE)
                                          .build())
                                  .build())
                          .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> receivedStatus = new AtomicReference<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            receivedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(receivedStatus.get().getDescription()).contains("External processor stream failed");
  }

  // ============================================================================
  // Category 25: Concurrency and Thread Safety (Serialization)
  // ============================================================================

  private static class ConcurrencyDetectingServerCall
      extends io.grpc.ForwardingServerCall.SimpleForwardingServerCall<InputStream, InputStream> {
    private final AtomicInteger activeCalls = new AtomicInteger(0);
    private final AtomicBoolean concurrentCallDetected = new AtomicBoolean(false);

    ConcurrencyDetectingServerCall(ServerCall<InputStream, InputStream> delegate) {
      super(delegate);
    }

    @Override
    public void sendMessage(InputStream message) {
      int active = activeCalls.incrementAndGet();
      if (active > 1) {
        concurrentCallDetected.set(true);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      super.sendMessage(message);
      activeCalls.decrementAndGet();
    }

    @Override
    public void sendHeaders(Metadata headers) {
      int active = activeCalls.incrementAndGet();
      if (active > 1) {
        concurrentCallDetected.set(true);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      super.sendHeaders(headers);
      activeCalls.decrementAndGet();
    }
  }

  @Test
  public void serverInterceptor_concurrency_serializesDelegateCallbacks() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final int iterations = 500;
    final CountDownLatch clientDone = new CountDownLatch(1);
    final AtomicBoolean raceDetected = new AtomicBoolean(false);
    final AtomicInteger activeServiceCalls = new AtomicInteger(0);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                } else if (request.hasRequestBody()) {
                  boolean eos =
                      request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage();
                  BodyResponse.Builder bodyResponseBuilder = BodyResponse.newBuilder();
                  if (eos) {
                    bodyResponseBuilder.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStream(
                                                request.getRequestBody().getEndOfStream())
                                            .setEndOfStreamWithoutMessage(
                                                request
                                                    .getRequestBody()
                                                    .getEndOfStreamWithoutMessage())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    ByteString body = request.getRequestBody().getBody();
                    bodyResponseBuilder.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder().setBody(body).build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(bodyResponseBuilder.build())
                          .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  boolean eos =
                      request.getResponseBody().getEndOfStream()
                          || request.getResponseBody().getEndOfStreamWithoutMessage();
                  BodyResponse.Builder bodyResponseBuilder = BodyResponse.newBuilder();
                  if (eos) {
                    bodyResponseBuilder.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder().build())
                                    .build())
                            .build());
                  } else {
                    ByteString body = request.getResponseBody().getBody();
                    bodyResponseBuilder.setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder().setBody(body).build())
                                    .build())
                            .build());
                  }
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(bodyResponseBuilder.build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(
              StreamObserver<InputStream> responseObserver) {
            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream value) {
                int active = activeServiceCalls.incrementAndGet();
                if (active > 1) {
                  raceDetected.set(true);
                }
                try {
                  Thread.sleep(1);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                activeServiceCalls.decrementAndGet();
                responseObserver.onNext(value);
              }

              @Override
              public void onError(Throwable t) {
                responseObserver.onError(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch clientClosedLatch = new CountDownLatch(1);
    final AtomicInteger clientReceivedMessages = new AtomicInteger(0);

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            clientReceivedMessages.incrementAndGet();
            clientCall.request(1);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            clientClosedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);

    Thread clientThread =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < iterations; i++) {
                  clientCall.sendMessage(new ByteArrayInputStream(new byte[10]));
                  Thread.sleep(0, 10000);
                }
                clientCall.halfClose();
                clientDone.countDown();
              } catch (Exception e) {
                e.printStackTrace();
                raceDetected.set(true);
              }
            });

    clientThread.start();
    clientThread.join();

    assertThat(clientDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages.get()).isEqualTo(iterations);
    assertThat(raceDetected.get()).isFalse();
  }

  @Test
  public void serverInterceptor_outboundStreamTermination_serializesSendMessage() throws Exception {
    // Configure response body mode to GRPC
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setFailureModeAllow(true)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch streamActiveLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            streamActiveLatch.countDown();
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> wrappedCallRef =
        new AtomicReference<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @SuppressWarnings("unchecked")
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            wrappedCallRef.set((ServerCall<InputStream, InputStream>) call);
            return next.startCall(call, headers);
          }
        };

    final AtomicReference<ConcurrencyDetectingServerCall> rawCallRef = new AtomicReference<>();
    ServerInterceptor capturingRawCallInterceptor =
        new ServerInterceptor() {
          @SuppressWarnings("unchecked")
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ConcurrencyDetectingServerCall wrapped =
                new ConcurrencyDetectingServerCall((ServerCall<InputStream, InputStream>) call);
            rawCallRef.set(wrapped);
            return next.startCall((ServerCall<ReqT, RespT>) (ServerCall<?, ?>) wrapped, headers);
          }
        };

    startDataPlane(capturingRawCallInterceptor, interceptor, capturingInterceptor);

    Metadata initialHeaders = new Metadata();
    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        initialHeaders);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
    responseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    ServerCall<InputStream, InputStream> wrappedCall = wrappedCallRef.get();
    assertThat(wrappedCall).isNotNull();
    wrappedCall.sendHeaders(new Metadata());

    ConcurrencyDetectingServerCall rawCall = rawCallRef.get();
    assertThat(rawCall).isNotNull();

    responseObserver.onError(new RuntimeException("Stream dropped"));

    final CountDownLatch messageSentLatch = new CountDownLatch(2);
    Thread appThread =
        new Thread(
            () -> {
              try {
                wrappedCall.sendMessage(
                    new ByteArrayInputStream("app-msg".getBytes(StandardCharsets.UTF_8)));
                messageSentLatch.countDown();
              } catch (Exception e) {
                // ignore
              }
            });

    wrappedCall.sendMessage(
        new ByteArrayInputStream("buffered-msg".getBytes(StandardCharsets.UTF_8)));
    messageSentLatch.countDown();

    appThread.start();
    appThread.join();

    boolean sent = messageSentLatch.await(5, TimeUnit.SECONDS);
    assertThat(sent).isTrue();
    assertThat(rawCall.concurrentCallDetected.get()).isFalse();
  }

  @Test
  public void serverInterceptor_concurrentSendHeadersAndFailOpen_flushesHeadersCorrectly()
      throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).setFailureModeAllow(true).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch streamActiveLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            streamActiveLatch.countDown();
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> wrappedCallRef =
        new AtomicReference<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @SuppressWarnings("unchecked")
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            wrappedCallRef.set((ServerCall<InputStream, InputStream>) call);
            return next.startCall(call, headers);
          }
        };

    final AtomicReference<ConcurrencyDetectingServerCall> rawCallRef = new AtomicReference<>();
    ServerInterceptor capturingRawCallInterceptor =
        new ServerInterceptor() {
          @SuppressWarnings("unchecked")
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ConcurrencyDetectingServerCall wrapped =
                new ConcurrencyDetectingServerCall((ServerCall<InputStream, InputStream>) call);
            rawCallRef.set(wrapped);
            return next.startCall((ServerCall<ReqT, RespT>) (ServerCall<?, ?>) wrapped, headers);
          }
        };

    startDataPlane(capturingRawCallInterceptor, interceptor, capturingInterceptor);

    Metadata initialHeaders = new Metadata();
    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch headersReceivedLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> receivedResponseHeadersRef = new AtomicReference<>();
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeadersRef.set(headers);
            headersReceivedLatch.countDown();
          }
        },
        initialHeaders);

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    StreamObserver<ProcessingResponse> responseObserver = responseObserverRef.get();
    responseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    ServerCall<InputStream, InputStream> wrappedCall = wrappedCallRef.get();
    assertThat(wrappedCall).isNotNull();

    ConcurrencyDetectingServerCall rawCall = rawCallRef.get();
    assertThat(rawCall).isNotNull();

    Thread appThread =
        new Thread(
            () -> {
              Metadata headers = new Metadata();
              headers.put(
                  Metadata.Key.of("x-resp-header", Metadata.ASCII_STRING_MARSHALLER), "val");
              wrappedCall.sendHeaders(headers);
              wrappedCall.close(Status.OK, new Metadata());
            });

    appThread.start();
    responseObserver.onError(new RuntimeException("Stream failure"));

    appThread.join();

    boolean headersSent = headersReceivedLatch.await(5, TimeUnit.SECONDS);
    assertThat(headersSent).isTrue();
    assertThat(
            receivedResponseHeadersRef
                .get()
                .get(Metadata.Key.of("x-resp-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("val");
    assertThat(rawCall.concurrentCallDetected.get()).isFalse();
  }

  // Category 26: Request-Scoped Context Propagation
  @Test
  public void serverInterceptor_contextPropagatedToStartCall() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerInterceptor contextAttachingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context contextWithKey = Context.current().withValue(TRACE_KEY, "test-trace-123");
            return Contexts.interceptCall(contextWithKey, call, headers, next);
          }
        };

    final AtomicBoolean startCallVerified = new AtomicBoolean(false);

    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            if ("test-trace-123".equals(TRACE_KEY.get())) {
              startCallVerified.set(true);
            }
            return next.startCall(call, headers);
          }
        };

    startDataPlane(capturingInterceptor, interceptor, contextAttachingInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(startCallVerified.get()).isTrue();
  }

  @Test
  public void serverInterceptor_contextPropagatedToListenerCallbacks() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerInterceptor contextAttachingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context contextWithKey = Context.current().withValue(TRACE_KEY, "test-trace-123");
            return Contexts.interceptCall(contextWithKey, call, headers, next);
          }
        };

    final AtomicBoolean onMessageVerified = new AtomicBoolean(false);
    final AtomicBoolean onHalfCloseVerified = new AtomicBoolean(false);
    final AtomicBoolean onReadyVerified = new AtomicBoolean(false);

    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new ServerCall.Listener<ReqT>() {
              @Override
              public void onMessage(ReqT message) {
                if ("test-trace-123".equals(TRACE_KEY.get())) {
                  onMessageVerified.set(true);
                }
                nextListener.onMessage(message);
              }

              @Override
              public void onHalfClose() {
                if ("test-trace-123".equals(TRACE_KEY.get())) {
                  onHalfCloseVerified.set(true);
                }
                nextListener.onHalfClose();
              }

              @Override
              public void onReady() {
                if ("test-trace-123".equals(TRACE_KEY.get())) {
                  onReadyVerified.set(true);
                }
                nextListener.onReady();
              }
            };
          }
        };

    startDataPlane(capturingInterceptor, interceptor, contextAttachingInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(onMessageVerified.get()).isTrue();
    assertThat(onHalfCloseVerified.get()).isTrue();
    assertThat(onReadyVerified.get()).isTrue();
  }

  @Test
  public void serverInterceptor_contextPropagatedToExtProcStub() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(CommonResponse.newBuilder().build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    final AtomicBoolean extProcStubContextVerified = new AtomicBoolean(false);
    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName)
                        .directExecutor()
                        .intercept(
                            new io.grpc.ClientInterceptor() {
                              @Override
                              public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                  MethodDescriptor<ReqT, RespT> method,
                                  io.grpc.CallOptions callOptions,
                                  io.grpc.Channel next) {
                                if (method
                                    .getFullMethodName()
                                    .equals(
                                        ExternalProcessorGrpc.getProcessMethod()
                                            .getFullMethodName())) {
                                  if ("test-trace-123".equals(TRACE_KEY.get())) {
                                    extProcStubContextVerified.set(true);
                                  }
                                }
                                return next.newCall(method, callOptions);
                              }
                            })
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerInterceptor contextAttachingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context contextWithKey = Context.current().withValue(TRACE_KEY, "test-trace-123");
            return Contexts.interceptCall(contextWithKey, call, headers, next);
          }
        };

    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(call, headers);
          }
        };

    startDataPlane(capturingInterceptor, interceptor, contextAttachingInterceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(extProcStubContextVerified.get()).isTrue();
  }

  // ============================================================================
  // Category 27: Ascii and binary aspects of serialization
  // ============================================================================
  @Test
  public void serialization_specCompliance() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseHeaders()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    ServerInterceptor headersInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void sendHeaders(Metadata responseHeaders) {
                    responseHeaders.put(
                        Metadata.Key.of("custom-ascii", Metadata.ASCII_STRING_MARSHALLER),
                        "hello-world");
                    responseHeaders.put(
                        Metadata.Key.of("custom-bin", Metadata.BINARY_BYTE_MARSHALLER),
                        new byte[] {0x00, 0x01, 0x02});
                    super.sendHeaders(responseHeaders);
                  }
                },
                headers);
          }
        };

    startDataPlane(headersInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    ProcessingRequest req = capturedRequest.get();
    assertThat(req).isNotNull();

    io.envoyproxy.envoy.config.core.v3.HeaderMap headerMap = req.getResponseHeaders().getHeaders();
    io.envoyproxy.envoy.config.core.v3.HeaderValue customAsciiProto = null;
    io.envoyproxy.envoy.config.core.v3.HeaderValue customBinProto = null;
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv : headerMap.getHeadersList()) {
      if (hv.getKey().equals("custom-ascii")) {
        customAsciiProto = hv;
      } else if (hv.getKey().equals("custom-bin")) {
        customBinProto = hv;
      }
    }

    assertThat(customAsciiProto).isNotNull();
    assertThat(customAsciiProto.getValue()).isEmpty();
    assertThat(customAsciiProto.getRawValue().toStringUtf8()).isEqualTo("hello-world");

    assertThat(customBinProto).isNotNull();
    assertThat(customBinProto.getValue()).isEmpty();
    String expectedBase64 =
        com.google.common.io.BaseEncoding.base64().encode(new byte[] {0x00, 0x01, 0x02});
    assertThat(customBinProto.getRawValue().toStringUtf8()).isEqualTo(expectedBase64);
  }

  @Test
  public void deserialization_preferRawValue() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-ascii")
                                                                  .setValue("legacy-val")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "raw-val"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedResponseHeaders = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeaders.set(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedResponseHeaders.get()).isNotNull();
    assertThat(
            receivedResponseHeaders
                .get()
                .get(Metadata.Key.of("custom-ascii", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("raw-val");
  }

  @Test
  public void deserialization_binaryHeader_validBase64() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-bin")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "YmFy"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Metadata> receivedResponseHeaders = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onHeaders(Metadata headers) {
            receivedResponseHeaders.set(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedResponseHeaders.get()).isNotNull();
    byte[] binValue =
        receivedResponseHeaders
            .get()
            .get(Metadata.Key.of("custom-bin", Metadata.BINARY_BYTE_MARSHALLER));
    assertThat(binValue).isEqualTo(new byte[] {'b', 'a', 'r'});
  }

  @Test
  public void deserialization_binaryHeader_invalidBase64_noError_fails() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-bin")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "invalid_base64!"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void deserialization_binaryHeader_invalidBase64_failsCall() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setMutationRules(
                io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
                    .setDisallowIsError(com.google.protobuf.BoolValue.of(true))
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-bin")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "invalid_base64!"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void deserialization_asciiHeader_invalidChars_noError_fails() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-ascii")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "value_with_newline\n"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void deserialization_asciiHeader_invalidCharacters_failsCall() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setMutationRules(
                io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
                    .setDisallowIsError(com.google.protobuf.BoolValue.of(true))
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-ascii")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          "value_with_newline\n"))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void deserialization_headerValue_tooLong_noError_fails() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  String longVal = com.google.common.base.Strings.repeat("a", 16385);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-ascii")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          longVal))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void deserialization_headerValue_tooLong_failsCall() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setMutationRules(
                io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
                    .setDisallowIsError(com.google.protobuf.BoolValue.of(true))
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(2);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  String longVal = com.google.common.base.Strings.repeat("a", 16385);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseHeaders(
                              HeadersResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setHeaderMutation(
                                              HeaderMutation.newBuilder()
                                                  .addSetHeaders(
                                                      io.envoyproxy.envoy.config.core.v3
                                                          .HeaderValueOption.newBuilder()
                                                          .setHeader(
                                                              io.envoyproxy.envoy.config.core.v3
                                                                  .HeaderValue.newBuilder()
                                                                  .setKey("custom-ascii")
                                                                  .setRawValue(
                                                                      ByteString.copyFromUtf8(
                                                                          longVal))
                                                                  .build())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<Status> callStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get()).isNotNull();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(callStatus.get().getDescription()).contains("External processor stream failed");
  }

  @Test
  public void givenRequestBodyModeGrpc_whenClientSendsEmptyMessage_thenEmptyMessageIsDelivered()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setEndOfStream(true)
                                                          .setEndOfStreamWithoutMessage(
                                                              request
                                                                  .getRequestBody()
                                                                  .getEndOfStreamWithoutMessage())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<InputStream> receivedRequest = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            try {
              receivedRequest.set(request);
              responseObserver.onNext(
                  new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            } catch (Throwable t) {
              responseObserver.onError(t);
            }
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream(new byte[0]));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest req = capturedRequest.get();
    assertThat(req).isNotNull();
    assertThat(req.getRequestBody().getBody().isEmpty()).isTrue();
    assertThat(req.getRequestBody().getEndOfStreamWithoutMessage()).isFalse();

    InputStream serverReceivedStream = receivedRequest.get();
    assertThat(serverReceivedStream).isNotNull();
    assertThat(serverReceivedStream.available()).isEqualTo(0);
  }

  @Test
  public void givenResponseBodyModeGrpc_whenServerSendsEmptyMessage_thenEmptyMessageIsDelivered()
      throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseBody()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseBody(
                              BodyResponse.newBuilder()
                                  .setResponse(
                                      CommonResponse.newBuilder()
                                          .setBodyMutation(
                                              BodyMutation.newBuilder()
                                                  .setStreamedResponse(
                                                      StreamedBodyResponse.newBuilder()
                                                          .setBody(ByteString.EMPTY)
                                                          .setEndOfStream(
                                                              request
                                                                  .getResponseBody()
                                                                  .getEndOfStream())
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setResponseTrailers(TrailersResponse.newBuilder().build())
                          .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public void sayHello(InputStream request, StreamObserver<InputStream> responseObserver) {
            responseObserver.onNext(
                new ByteArrayInputStream(new byte[0])); // Empty response message!
            responseObserver.onCompleted();
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final AtomicReference<InputStream> receivedResponse = new AtomicReference<>();
    final CountDownLatch clientLatch = new CountDownLatch(1);
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            receivedResponse.set(message);
            clientLatch.countDown();
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ProcessingRequest req = capturedRequest.get();
    assertThat(req).isNotNull();
    assertThat(req.getResponseBody().getBody().isEmpty()).isTrue();

    InputStream clientReceivedStream = receivedResponse.get();
    assertThat(clientReceivedStream).isNotNull();
    assertThat(clientReceivedStream.available()).isEqualTo(0);
  }

  // ============================================================================
  // Category 28: Application level flow control and draining
  // ============================================================================
  @Test
  public void testFlowControlStateInitialization() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch extProcLatch = new CountDownLatch(2);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            System.out.println("extProcImpl.process called");
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                receivedRequests.add(request);
                extProcLatch.countDown();
                if (request.hasRequestHeaders()) {
                  System.out.println("extProcImpl: responding to request headers");
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  boolean isEOS = request.getRequestBody().getEndOfStream();
                  boolean isEOSWithoutMsg = request.getRequestBody().getEndOfStreamWithoutMessage();
                  if (isEOSWithoutMsg || (isEOS && request.getRequestBody().getBody().isEmpty())) {
                    System.out.println("extProcImpl: sending EOS without message");
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStreamWithoutMessage(true)
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  } else {
                    System.out.println("extProcImpl: sending body response, EOS=" + isEOS);
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(request.getRequestBody().getBody())
                                        .setEndOfStream(isEOS)
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  }
                } else if (request.hasResponseHeaders()) {
                  System.out.println("extProcImpl: responding to response headers");
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  System.out.println("extProcImpl: responding to response body");
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(request.getResponseBody().getBody())
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                } else if (request.hasResponseTrailers()) {
                  System.out.println("extProcImpl: responding to response trailers");
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                }
              }

              @Override
              public void onError(Throwable t) {
                System.out.println("extProcImpl.onError: " + t);
              }

              @Override
              public void onCompleted() {
                System.out.println("extProcImpl.onCompleted");
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_RAW, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onHeaders(Metadata headers) {
        System.out.println("clientCall.onHeaders");
      }
      @Override
      public void onMessage(InputStream message) {
        System.out.println("clientCall.onMessage");
      }
      @Override
      public void onClose(Status status, Metadata trailers) {
        System.out.println("clientCall.onClose: " + status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();

    assertThat(receivedRequests).hasSize(6);
    ProcessingRequest firstRequest = receivedRequests.get(0);
    ProcessingRequest secondRequest = receivedRequests.get(1);
    ProcessingRequest thirdRequest = receivedRequests.get(2);
    ProcessingRequest fourthRequest = receivedRequests.get(3);
    ProcessingRequest fifthRequest = receivedRequests.get(4);
    ProcessingRequest sixthRequest = receivedRequests.get(5);

    assertThat(firstRequest.hasRequestHeaders()).isTrue();
    assertThat(firstRequest.hasFlowControlInit()).isTrue();
    assertThat(firstRequest.getFlowControlInit().getInitialWindowDownstreamToSidestream())
        .isEqualTo(65536);
    assertThat(firstRequest.getFlowControlInit().getInitialWindowSidestreamToUpstream())
        .isEqualTo(65536);

    assertThat(secondRequest.hasRequestBody()).isTrue();
    assertThat(secondRequest.hasFlowControlInit()).isFalse();

    assertThat(thirdRequest.hasRequestBody()).isTrue();
    assertThat(thirdRequest.getRequestBody().getEndOfStreamWithoutMessage()).isTrue();
    assertThat(thirdRequest.hasClientWindowUpdate()).isTrue();
    assertThat(thirdRequest.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())
        .isEqualTo(5);

    assertThat(fourthRequest.hasResponseHeaders()).isTrue();
    assertThat(fifthRequest.hasResponseBody()).isTrue();
    assertThat(sixthRequest.hasResponseTrailers()).isTrue();
  }

  @Test
  public void testDownstreamToSidestreamFlowControl_EnforcesWindow() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch firstBodyLatch = new CountDownLatch(2); // Headers + First Body
    final CountDownLatch secondBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  firstBodyLatch.countDown();
                } else if (request.hasRequestBody()) {
                  boolean isEOS = request.getRequestBody().getEndOfStream();
                  boolean isEOSWithoutMsg = request.getRequestBody().getEndOfStreamWithoutMessage();
                  if (isEOSWithoutMsg || (isEOS && request.getRequestBody().getBody().isEmpty())) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStreamWithoutMessage(true)
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  } else {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(request.getRequestBody().getBody())
                                        .setEndOfStream(isEOS)
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    if (firstBodyLatch.getCount() > 0) {
                      firstBodyLatch.countDown();
                    } else {
                      secondBodyLatch.countDown();
                    }
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> dataPlaneReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch serverAppCompletedLatch = new CountDownLatch(1);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloClientStreaming(
          StreamObserver<InputStream> responseObserver) {
        final io.grpc.stub.ServerCallStreamObserver<InputStream> serverCallStreamObserver =
            (io.grpc.stub.ServerCallStreamObserver<InputStream>) responseObserver;
        serverCallStreamObserver.disableAutoRequest();
        serverCallStreamObserver.request(2);
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            try {
              dataPlaneReceivedMessages.add(ByteString.readFrom(value));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onNext(new ByteArrayInputStream("Response".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onCompleted();
            serverAppCompletedLatch.countDown();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_CLIENT_STREAMING, io.grpc.CallOptions.DEFAULT);

    final List<ByteString> clientResponseMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);

    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {
        try {
          clientResponseMessages.add(ByteString.readFrom(message));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);

    // Generate large messages
    ByteString largeMessage70k = ByteString.copyFrom(new byte[70000]);
    ByteString largeMessage30k = ByteString.copyFrom(new byte[30000]);

    // Send first message (70000 bytes) - fits in 65536 window (sent immediately)
    clientCall.sendMessage(new ByteArrayInputStream(largeMessage70k.toByteArray()));
    assertThat(firstBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Send second message (30000 bytes) - total 100000 > 65536, should be blocked in transport
    clientCall.sendMessage(new ByteArrayInputStream(largeMessage30k.toByteArray()));

    Thread.sleep(100);

    // Verify it is NOT delivered immediately
    assertThat(receivedRequests).hasSize(3);
    assertThat(receivedRequests.get(0).hasRequestHeaders()).isTrue();
    assertThat(receivedRequests.get(1).hasRequestBody()).isTrue();
    assertThat(receivedRequests.get(2).hasClientWindowUpdate()).isTrue();

    // Client half-closes
    clientCall.halfClose();

    // Now send ServerWindowUpdate from ext_proc to interceptor to increment window by 40000
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementDownstreamToSidestream(40000)
            .build())
        .build());

    // The second body should now be flushed and received by ext_proc
    assertThat(secondBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Wait for the server App to complete and call to close successfully.
    assertThat(serverAppCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(dataPlaneReceivedMessages)
        .containsExactly(largeMessage70k, largeMessage30k).inOrder();
    assertThat(clientResponseMessages)
        .containsExactly(ByteString.copyFromUtf8("Response"));

  }
  @Test
  public void serverInterceptor_deferredClose_pendingResponseMessages() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch extProcLatch1 = new CountDownLatch(1);
    final CountDownLatch extProcLatch2 = new CountDownLatch(2);
    final CountDownLatch extProcLatch3 = new CountDownLatch(3);
    final CountDownLatch extProcLatch4 = new CountDownLatch(4);
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("JETS_LOG: extProc received request: " + request.getRequestCase());
                receivedRequests.add(request);
                extProcLatch1.countDown();
                extProcLatch2.countDown();
                extProcLatch3.countDown();
                extProcLatch4.countDown();
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(uniqueExtProcServerName)
                        .directExecutor()
                        .build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final byte[] largeMessageBytes = new byte[70000];
    java.util.Arrays.fill(largeMessageBytes, (byte) 'a');
    final byte[] smallMessageBytes = new byte[10000];
    java.util.Arrays.fill(smallMessageBytes, (byte) 'b');

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(
              final StreamObserver<InputStream> responseObserver) {
            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream value) {
                responseObserver.onNext(new ByteArrayInputStream(largeMessageBytes));
                responseObserver.onNext(new ByteArrayInputStream(smallMessageBytes));
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    startDataPlane(interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final List<ByteString> receivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();

    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onMessage(InputStream message) {
            System.out.println("JETS_LOG: client.onMessage");
            try {
              receivedMessages.add(ByteString.readFrom(message));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            System.out.println("JETS_LOG: client.onClose: " + status);
            closedStatus.set(status);
            callCompletedLatch.countDown();
          }
        },
        new Metadata());

    clientCall.request(2);
    System.out.println("JETS_LOG: client calling sendMessage");
    clientCall.sendMessage(new ByteArrayInputStream("msg".getBytes(StandardCharsets.UTF_8)));
    System.out.println("JETS_LOG: client calling halfClose");
    clientCall.halfClose();

    // Verify ext_proc received Message 1
    assertThat(extProcLatch1.await(5, TimeUnit.SECONDS)).isTrue();
    // Verify call is NOT completed yet
    assertThat(callCompletedLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(extProcLatch2.getCount()).isEqualTo(1);
    // Client should not have received any messages yet (waiting for ext_proc response)
    assertThat(receivedMessages).isEmpty();

    // Respond to Message 1 and grant window to drain Message 2
    StreamObserver<ProcessingResponse> extProcResponseObserver = extProcResponseObserverRef.get();
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setResponseBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(receivedRequests.get(0).getResponseBody().getBody())
                                            .build())
                                    .build())
                            .build())
                    .build())
            .setServerWindowUpdate(
                ProcessingResponse.ServerWindowUpdate.newBuilder()
                    .setWindowIncrementUpstreamToSidestream(70000)
                    .build())
            .build());

    // Verify ext_proc received Message 2
    assertThat(extProcLatch2.await(5, TimeUnit.SECONDS)).isTrue();

    // At this point, Message 1 should be delivered to client, but not Message 2 yet.
    // Wait, since we are using directExecutor, the delivery might happen synchronously.
    // Let's check if Message 1 is delivered.
    assertThat(receivedMessages).hasSize(1);
    assertThat(receivedMessages.get(0)).isEqualTo(ByteString.copyFrom(largeMessageBytes));

    // Respond to Message 2
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setResponseBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(receivedRequests.get(1).getResponseBody().getBody())
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());

    // Verify ext_proc received Response Trailers
    assertThat(extProcLatch4.await(5, TimeUnit.SECONDS)).isTrue();
    // Respond to Response Trailers
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setResponseTrailers(TrailersResponse.newBuilder().build())
            .build());

    // Now call should complete and Message 2 should be delivered
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();

    assertThat(receivedRequests).hasSize(4);
    assertThat(receivedRequests.get(0).hasResponseBody()).isTrue();
    assertThat(receivedRequests.get(0).getResponseBody().getBody()).isEqualTo(ByteString.copyFrom(largeMessageBytes));
    assertThat(receivedRequests.get(1).hasResponseBody()).isTrue();
    assertThat(receivedRequests.get(1).getResponseBody().getBody()).isEqualTo(ByteString.copyFrom(smallMessageBytes));
    assertThat(receivedRequests.get(2).hasClientWindowUpdate()).isTrue();
    assertThat(receivedRequests.get(3).hasResponseTrailers()).isTrue();

    assertThat(receivedMessages).hasSize(2);
    assertThat(receivedMessages.get(0)).isEqualTo(ByteString.copyFrom(largeMessageBytes));
    assertThat(receivedMessages.get(1)).isEqualTo(ByteString.copyFrom(smallMessageBytes));

    channelManager.close();
  }

  @Test
  public void testDownstreamToSidestreamFlowControl_deferredHalfClose() throws Exception {
    ExternalProcessor proto =
        createBaseProto(extProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch1 = new CountDownLatch(1); // Msg 1 (headers)
    final CountDownLatch extProcLatch2 = new CountDownLatch(2); // Msg 1 body, Msg 2 body
    final CountDownLatch extProcLatch3 = new CountDownLatch(1); // EOS empty msg
    final AtomicReference<StreamObserver<ProcessingResponse>> extProcResponseObserverRef =
        new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            extProcResponseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("JETS_LOG: ext_proc.onNext: " + request);
                if (request.hasRequestHeaders()) {
                  extProcLatch1.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    extProcLatch3.countDown();
                  } else {
                    extProcLatch2.countDown();
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config ->
                grpcCleanup.register(
                    InProcessChannelBuilder.forName(extProcServerName).directExecutor().build()));

    ExternalProcessorServerInterceptor interceptor =
        new ExternalProcessorServerInterceptor(filterConfig, channelManager, FAKE_CONTEXT);

    final CountDownLatch appHalfCloseLatch = new CountDownLatch(1);
    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ServerInterceptor capturingInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            ServerCall.Listener<ReqT> nextListener = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(nextListener) {
              @Override
              @SuppressWarnings("unchecked")
              public void onMessage(ReqT message) {
                try {
                  byte[] bytes = ByteStreams.toByteArray((InputStream) message);
                  serverReceivedMessages.add(new String(bytes, StandardCharsets.UTF_8));
                  super.onMessage((ReqT) new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onHalfClose() {
                appHalfCloseLatch.countDown();
                super.onHalfClose();
              }
            };
          }
        };

    dataPlaneHandler =
        new DataPlaneServiceHandler() {
          @Override
          public StreamObserver<InputStream> sayHelloBidi(
              StreamObserver<InputStream> responseObserver) {
            return new StreamObserver<InputStream>() {
              @Override
              public void onNext(InputStream value) {
                responseObserver.onNext(value);
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    startDataPlane(capturingInterceptor, interceptor);

    io.grpc.ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(
        new io.grpc.ClientCall.Listener<InputStream>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            callCompletedLatch.countDown();
          }
        },
        new Metadata());
    clientCall.request(2);

    // Send Message 1 of size 65536 bytes to exhaust the window
    byte[] largeMessageBytes = new byte[65536];
    java.util.Arrays.fill(largeMessageBytes, (byte) 'a');
    clientCall.sendMessage(new ByteArrayInputStream(largeMessageBytes));

    // Verify ext_proc received headers
    assertThat(extProcLatch1.await(5, TimeUnit.SECONDS)).isTrue();

    // Respond to headers to allow messages
    StreamObserver<ProcessingResponse> extProcResponseObserver = extProcResponseObserverRef.get();
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestHeaders(
                HeadersResponse.newBuilder()
                    .setResponse(CommonResponse.newBuilder().build())
                    .build())
            .build());

    // Wait for ext_proc to receive Message 1 body
    long startTime = System.currentTimeMillis();
    while (extProcLatch2.getCount() > 1 && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(50);
    }
    assertThat(extProcLatch2.getCount()).isEqualTo(1);

    // Now window is 0.
    // Send Message 2 of size 1 byte. It should be buffered.
    clientCall.sendMessage(new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));

    // Client half-closes
    clientCall.halfClose();

    // Verify half-close is deferred (app listener not called)
    assertThat(appHalfCloseLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();
    // Verify server app didn't receive any messages yet (Msg 1 is still in ext_proc processing, Msg 2 is buffered)
    assertThat(serverReceivedMessages).isEmpty();

    // Respond to Message 1 body, and grant window to drain Message 2
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(ByteString.copyFrom(largeMessageBytes))
                                            .build())
                                    .build())
                            .build())
                    .build())
            .setServerWindowUpdate(
                ProcessingResponse.ServerWindowUpdate.newBuilder()
                    .setWindowIncrementDownstreamToSidestream(1000)
                    .build())
            .build());

    // Verify ext_proc received Message 2 body
    assertThat(extProcLatch2.await(5, TimeUnit.SECONDS)).isTrue();

    // Respond to Message 2 body
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setBody(ByteString.copyFromUtf8("b"))
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());

    // Verify ext_proc received empty EOS message
    assertThat(extProcLatch3.await(5, TimeUnit.SECONDS)).isTrue();

    // Respond to empty EOS message
    extProcResponseObserver.onNext(
        ProcessingResponse.newBuilder()
            .setRequestBody(
                BodyResponse.newBuilder()
                    .setResponse(
                        CommonResponse.newBuilder()
                            .setBodyMutation(
                                BodyMutation.newBuilder()
                                    .setStreamedResponse(
                                        StreamedBodyResponse.newBuilder()
                                            .setEndOfStreamWithoutMessage(true)
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());

    // Now half-close should be propagated to app, and messages delivered
    assertThat(appHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly(new String(largeMessageBytes, StandardCharsets.UTF_8), "b");

    // Verify call completes
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();
  }


  @Test
  public void testFailOpen_DrainsInboundQueuesInOrder() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();
    final CountDownLatch extProcActiveLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            extProcActiveLatch.countDown();
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
              }
              @Override
              public void onError(Throwable t) {}
              @Override
              public void onCompleted() {}
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ExternalProcessorServerInterceptor.DataPlaneServerCall>
        serverCallRef = new AtomicReference<>();

    ServerInterceptor captureInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          Metadata headers,
          ServerCallHandler<ReqT, RespT> next) {
        serverCallRef.set((ExternalProcessorServerInterceptor.DataPlaneServerCall) call);
        return next.startCall(call, headers);
      }
    };

    final List<ByteString> dataPlaneReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appCompletedLatch = new CountDownLatch(1);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          StreamObserver<InputStream> responseObserver) {
        ServerCallStreamObserver<InputStream> serverCallObserver =
            (ServerCallStreamObserver<InputStream>) responseObserver;
        serverCallObserver.disableAutoRequest();
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            try {
              dataPlaneReceivedMessages.add(ByteString.readFrom(value));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
            appCompletedLatch.countDown();
          }
        };
      }
    };

    // Start data plane with both interceptors
    dataPlaneServerName = InProcessServerBuilder.generateName();
    ServerServiceDefinition dataPlaneService =
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_SAY_HELLO_BIDI,
                io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
                    responseObserver -> dataPlaneHandler.sayHelloBidi(responseObserver)))
            .build();

    grpcCleanup.register(
        InProcessServerBuilder.forName(dataPlaneServerName)
            .addService(
                ServerInterceptors.intercept(
                    dataPlaneService, java.util.Arrays.asList(captureInterceptor, interceptor)))
            .directExecutor()
            .build()
            .start());

    dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {}
      @Override
      public void onClose(Status status, Metadata trailers) {}
    }, new Metadata());

    // Wait for ExtProc stream to be active
    assertThat(extProcActiveLatch.await(5, TimeUnit.SECONDS)).isTrue();

    ExternalProcessorServerInterceptor.DataPlaneServerCall dataPlaneCall = serverCallRef.get();
    assertThat(dataPlaneCall).isNotNull();

    ExternalProcessorServerInterceptor.DataPlaneServerListener listener =
        dataPlaneCall.getListener();
    assertThat(listener).isNotNull();

    // Manually populate queues
    synchronized (dataPlaneCall.streamLock) {
      dataPlaneCall.pendingMutatedRequestBodies.add(
          StreamedBodyResponse.newBuilder()
              .setBody(ByteString.copyFromUtf8("mutated-1"))
              .build());
      dataPlaneCall.pendingRequestBodyMessages.add(ByteString.copyFromUtf8("raw-waiting-2"));
      listener.savedMessages.add(new ByteArrayInputStream("raw-saved-3".getBytes(StandardCharsets.UTF_8)));
    }

    // Verify nothing received yet
    assertThat(dataPlaneReceivedMessages).isEmpty();

    // Fail the ext_proc stream to trigger fail-open
    responseObserverRef.get().onError(new RuntimeException("ExtProc stream failed"));

    assertThat(dataPlaneReceivedMessages).containsExactly(
        ByteString.copyFromUtf8("mutated-1"),
        ByteString.copyFromUtf8("raw-waiting-2"),
        ByteString.copyFromUtf8("raw-saved-3")
    ).inOrder();

    clientCall.halfClose();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testUpstreamToSidestreamFlowControl_EnforcesWindow() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(2); // Response Headers + Response Body 1
    final CountDownLatch secondResponseBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                receivedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString originalBody = request.getResponseBody().getBody();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(originalBody)
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                  if (originalBody.size() == 30000) {
                    secondResponseBodyLatch.countDown();
                  }
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch1_body = new CountDownLatch(1);
    final CountDownLatch messagesLatch2_body = new CountDownLatch(2);

    final ByteString largeMessage70k = ByteString.copyFrom(new byte[70000]);
    final ByteString largeMessage30k = ByteString.copyFrom(new byte[30000]);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            responseObserver.onNext(new ByteArrayInputStream(largeMessage70k.toByteArray()));
            responseObserver.onNext(new ByteArrayInputStream(largeMessage30k.toByteArray()));
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);

    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {
        try {
          clientReceivedMessages.add(ByteString.readFrom(message));
          messagesLatch1_body.countDown();
          messagesLatch2_body.countDown();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(10);

    clientCall.sendMessage(new ByteArrayInputStream("trigger".getBytes(StandardCharsets.UTF_8)));

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(messagesLatch1_body.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages).hasSize(1);
    assertThat(clientReceivedMessages.get(0).size()).isEqualTo(70000);

    assertThat(secondResponseBodyLatch.getCount()).isEqualTo(1);

    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementUpstreamToSidestream(40000)
            .build())
        .build());

    assertThat(secondResponseBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(messagesLatch2_body.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages).hasSize(2);
    assertThat(clientReceivedMessages.get(1).size()).isEqualTo(30000);

    assertThat(receivedRequests).isNotEmpty();

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testClientWindowUpdateSentImmediatelyOnSidestreamToDownstreamWindowExhaustion()
      throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(4); // Response Headers + Response Body 1 + 2 + 3
    final CountDownLatch windowUpdateLatch1 = new CountDownLatch(1);
    final CountDownLatch windowUpdateLatch2 = new CountDownLatch(2);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                receivedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
                          .setWindowIncrementUpstreamToSidestream(100000)
                          .build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString originalBody = request.getResponseBody().getBody();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(originalBody)
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                } else if (request.hasClientWindowUpdate()) {
                  windowUpdateLatch1.countDown();
                  windowUpdateLatch2.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch1_body = new CountDownLatch(1);
    final CountDownLatch messagesLatch2_body = new CountDownLatch(2);

    final ByteString body50k = ByteString.copyFrom(new byte[50000]);
    final ByteString body20k = ByteString.copyFrom(new byte[20000]);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            responseObserver.onNext(new ByteArrayInputStream(body50k.toByteArray()));
            responseObserver.onNext(new ByteArrayInputStream(body20k.toByteArray()));
            responseObserver.onNext(new ByteArrayInputStream(body50k.toByteArray()));
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {
        try {
          clientReceivedMessages.add(ByteString.readFrom(message));
          messagesLatch1_body.countDown();
          messagesLatch2_body.countDown();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(1);

    clientCall.sendMessage(new ByteArrayInputStream("trigger".getBytes(StandardCharsets.UTF_8)));

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(clientReceivedMessages).hasSize(1);
    assertThat(clientReceivedMessages.get(0).size()).isEqualTo(50000);

    assertThat(windowUpdateLatch1.await(5, TimeUnit.SECONDS)).isTrue();
    
    long updateValue1 = receivedRequests.stream()
        .filter(ProcessingRequest::hasClientWindowUpdate)
        .map(r -> r.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .findFirst().orElse(0L);
    assertThat(updateValue1).isEqualTo(50000L);

    clientCall.request(1);

    assertThat(windowUpdateLatch2.await(5, TimeUnit.SECONDS)).isTrue();

    long updateValue2 = receivedRequests.stream()
        .filter(ProcessingRequest::hasClientWindowUpdate)
        .map(r -> r.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .skip(1)
        .findFirst().orElse(0L);
    assertThat(updateValue2).isEqualTo(20000L);

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToUpstreamFlowControl_NegativeWindow() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(3); // Headers + Body 1 + Body 2
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStream(request.getRequestBody().getEndOfStream())
                                        .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    return;
                  }
                  int msgSeq = (int) sidecarLatch.getCount();
                  if (msgSeq == 2) { // 1st body
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(ByteString.copyFrom(new byte[70000]))
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    sidecarLatch.countDown();
                  } else if (msgSeq == 1) { // 2nd body
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(ByteString.copyFrom(new byte[10000]))
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    sidecarLatch.countDown();
                  }
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> dataPlaneReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch = new CountDownLatch(2);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            try {
              dataPlaneReceivedMessages.add(ByteString.readFrom(value));
              messagesLatch.countDown();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(2);

    clientCall.sendMessage(new ByteArrayInputStream("msg1".getBytes(StandardCharsets.UTF_8)));
    clientCall.sendMessage(new ByteArrayInputStream("msg2".getBytes(StandardCharsets.UTF_8)));

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(messagesLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneReceivedMessages).hasSize(2);
    assertThat(dataPlaneReceivedMessages.get(0).size()).isEqualTo(70000);
    assertThat(dataPlaneReceivedMessages.get(1).size()).isEqualTo(10000);

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testThresholdBasedWindowUpdates() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(3); // Response Headers + Response Body 1 + 2
    final CountDownLatch windowUpdateLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                receivedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString originalBody = request.getResponseBody().getBody();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(originalBody)
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                } else if (request.hasClientWindowUpdate()) {
                  windowUpdateLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch1 = new CountDownLatch(1);
    final CountDownLatch messagesLatch2 = new CountDownLatch(2);

    final ByteString body20k = ByteString.copyFrom(new byte[20000]);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            responseObserver.onNext(new ByteArrayInputStream(body20k.toByteArray()));
            responseObserver.onNext(new ByteArrayInputStream(body20k.toByteArray()));
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {
        try {
          clientReceivedMessages.add(ByteString.readFrom(message));
          messagesLatch1.countDown();
          messagesLatch2.countDown();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(1);

    clientCall.sendMessage(new ByteArrayInputStream("trigger".getBytes(StandardCharsets.UTF_8)));

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(messagesLatch1.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages).hasSize(1);

    Thread.sleep(1000);
    assertThat(windowUpdateLatch.getCount()).isEqualTo(1);

    clientCall.request(1);

    assertThat(messagesLatch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages).hasSize(2);

    assertThat(windowUpdateLatch.await(5, TimeUnit.SECONDS)).isTrue();

    long updateValue = receivedRequests.stream()
        .filter(ProcessingRequest::hasClientWindowUpdate)
        .map(r -> r.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .findFirst().orElse(0L);
    assertThat(updateValue).isEqualTo(40000L);

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFailOpenDrainsResponseQueues() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(3); // Response Headers + Body 1 (50k) + Body 2 (20k)
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString originalBody = request.getResponseBody().getBody();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(originalBody)
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .intercept(new io.grpc.ClientInterceptor() {
                @Override
                public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    io.grpc.CallOptions callOptions,
                    io.grpc.Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      sidecarListenerRef.set((Listener<ProcessingResponse>) responseListener);
                      super.start(responseListener, headers);
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final AtomicReference<ServerCall<InputStream, InputStream>> interceptedCallRef = new AtomicReference<>();
    final AtomicReference<ServerCall.Listener<InputStream>> interceptedListenerRef = new AtomicReference<>();
    ServerCallHandler<InputStream, InputStream> nextHandler = (call, headers) -> {
      interceptedCallRef.set(call);
      ServerCall.Listener<InputStream> listener = new ServerCall.Listener<InputStream>() {};
      interceptedListenerRef.set(listener);
      return listener;
    };

    final List<ByteString> sentToClient = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicBoolean rawCallReady = new AtomicBoolean(false);
    
    ServerCall<InputStream, InputStream> rawCall = new SimpleServerCall(METHOD_SAY_HELLO_RAW) {
      @Override
      public boolean isReady() {
        return rawCallReady.get();
      }

      @Override
      public void sendMessage(InputStream message) {
        try {
          sentToClient.add(ByteString.readFrom(message));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    };

    try {
      ServerCall.Listener<InputStream> interceptorListener =
          interceptor.interceptCall(rawCall, new Metadata(), nextHandler);

      Metadata responseHeaders = new Metadata();
      interceptedCallRef.get().sendHeaders(responseHeaders);

      long startTime = System.currentTimeMillis();
      while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
      }
      assertThat(sidecarListenerRef.get()).isNotNull();

      ByteString body50k = ByteString.copyFrom(new byte[50000]);
      interceptedCallRef.get().sendMessage(new ByteArrayInputStream(body50k.toByteArray()));

      ByteString body20k = ByteString.copyFrom(new byte[20000]);
      interceptedCallRef.get().sendMessage(new ByteArrayInputStream(body20k.toByteArray()));

      ByteString body3 = ByteString.copyFrom(new byte[50000]);
      interceptedCallRef.get().sendMessage(new ByteArrayInputStream(body3.toByteArray()));

      assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(sentToClient).isEmpty();

      responseObserverRef.get().onError(Status.INTERNAL.asException());

      Thread.sleep(1000);
      assertThat(sentToClient).isEmpty();

      rawCallReady.set(true);
      interceptorListener.onReady();

      long start = System.currentTimeMillis();
      while (sentToClient.size() < 3 && System.currentTimeMillis() - start < 5000) {
        Thread.sleep(10);
      }

      assertThat(sentToClient).hasSize(3);
      assertThat(sentToClient.get(0).size()).isEqualTo(50000);
      assertThat(sentToClient.get(1).size()).isEqualTo(20000);
      assertThat(sentToClient.get(2).size()).isEqualTo(50000);

    } finally {
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToDownstreamFlowControl_NegativeWindow() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(3); // Response Headers + Body 1 + Body 2
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  int msgSeq = (int) sidecarLatch.getCount();
                  if (msgSeq == 2) { // 1st body
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setResponseBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(ByteString.copyFrom(new byte[70000]))
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    sidecarLatch.countDown();
                  } else if (msgSeq == 1) { // 2nd body
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setResponseBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(ByteString.copyFrom(new byte[10000]))
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    sidecarLatch.countDown();
                  }
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch = new CountDownLatch(2);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            responseObserver.onNext(new ByteArrayInputStream("resp1".getBytes(StandardCharsets.UTF_8)));
            responseObserver.onNext(new ByteArrayInputStream("resp2".getBytes(StandardCharsets.UTF_8)));
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {
        try {
          clientReceivedMessages.add(ByteString.readFrom(message));
          messagesLatch.countDown();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(2);

    clientCall.sendMessage(new ByteArrayInputStream("trigger".getBytes(StandardCharsets.UTF_8)));

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(messagesLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(clientReceivedMessages).hasSize(2);
    assertThat(clientReceivedMessages.get(0).size()).isEqualTo(70000);
    assertThat(clientReceivedMessages.get(1).size()).isEqualTo(10000);

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAppPullModel() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1); // Body 1
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    System.out.println("extProcImpl sending EOS response: eos=" 
                        + request.getRequestBody().getEndOfStream() 
                        + ", eos_no_msg=" + request.getRequestBody().getEndOfStreamWithoutMessage());
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStream(request.getRequestBody().getEndOfStream())
                                        .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    return;
                  }
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(request.getRequestBody().getBody())
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessagesLatch = new CountDownLatch(1);
    final AtomicReference<ServerCallStreamObserver<InputStream>> serverCallObserverRef = new AtomicReference<>();

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        ServerCallStreamObserver<InputStream> serverCallObserver =
            (ServerCallStreamObserver<InputStream>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();
        serverCallObserverRef.set(serverCallObserver);

        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            try {
              appReceivedMessages.add(ByteString.readFrom(value));
              appMessagesLatch.countDown();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            System.out.println("DataPlaneServiceHandler.onCompleted");
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(1);

    clientCall.sendMessage(new ByteArrayInputStream("msg1".getBytes(StandardCharsets.UTF_8)));

    Thread.sleep(1000);
    assertThat(sidecarLatch.getCount()).isEqualTo(1);
    assertThat(appMessagesLatch.getCount()).isEqualTo(1);

    serverCallObserverRef.get().request(1);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appMessagesLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(1);
    assertThat(appReceivedMessages.get(0).toStringUtf8()).isEqualTo("msg1");

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMutatedRequestBodyBuffering() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1); // Body 1
    final CountDownLatch secondResponseLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStream(request.getRequestBody().getEndOfStream())
                                        .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                    return;
                  }
                  
                  // Respond to Body 1
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFromUtf8("mutated-msg1"))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();

                  // Spoof/Send unsolicited Body 2 (mutated-msg2)
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFromUtf8("mutated-msg2"))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  secondResponseLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, FAKE_CONTEXT);

    final List<ByteString> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessagesLatch1 = new CountDownLatch(1);
    final CountDownLatch appMessagesLatch2 = new CountDownLatch(2);
    final AtomicReference<ServerCallStreamObserver<InputStream>> serverCallObserverRef = new AtomicReference<>();

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        ServerCallStreamObserver<InputStream> serverCallObserver =
            (ServerCallStreamObserver<InputStream>) responseObserver;
        serverCallObserver.disableAutoInboundFlowControl();
        serverCallObserverRef.set(serverCallObserver);

        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            try {
              appReceivedMessages.add(ByteString.readFrom(value));
              appMessagesLatch1.countDown();
              appMessagesLatch2.countDown();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    
    clientCall.request(1);

    clientCall.sendMessage(new ByteArrayInputStream("msg1".getBytes(StandardCharsets.UTF_8)));

    serverCallObserverRef.get().request(1);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(secondResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(appMessagesLatch1.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(1);
    assertThat(appReceivedMessages.get(0).toStringUtf8()).isEqualTo("mutated-msg1");

    Thread.sleep(1000);
    assertThat(appReceivedMessages).hasSize(1);

    serverCallObserverRef.get().request(1);

    assertThat(appMessagesLatch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(2);
    assertThat(appReceivedMessages.get(1).toStringUtf8()).isEqualTo("mutated-msg2");

    clientCall.halfClose();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    channelManager.close();
  }

  // ============================================================================
  // Category 29: Metrics
  // ============================================================================
  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcCall_whenExecutionSucceeds_thenAll4MetricsAreRecorded() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(5); // Req Headers, Req Body, Req EOS, Resp Headers, Resp Trailers
    
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStream(request.getRequestBody().getEndOfStream())
                                        .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  } else {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(request.getRequestBody().getBody())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  }
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    // Mock MetricRecorder
    MetricRecorder mockMetricRecorder = Mockito.mock(MetricRecorder.class);
    Filter.FilterContext customContext = Filter.FilterContext.create(
        "envoy.filters.http.ext_proc", mockMetricRecorder);

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, customContext);

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {
            responseObserver.onNext(new ByteArrayInputStream("resp".getBytes(StandardCharsets.UTF_8)));
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };

    startDataPlane(interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {}

      @Override
      public void onClose(Status status, Metadata trailers) {
        callCompletedLatch.countDown();
      }
    }, new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("msg".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify metrics
    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.clientHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.clientHalfCloseDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.serverHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.serverTrailersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcCall_whenExecutionFails_thenAll4MetricsAreRecorded() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(5); // Req Headers, Req Body, Req EOS, Resp Headers, Resp Trailers
    
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                System.out.println("extProcImpl.onNext:\n" + request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setEndOfStream(request.getRequestBody().getEndOfStream())
                                        .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  } else {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(request.getRequestBody().getBody())
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build());
                  }
                  extProcLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                } else if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                  extProcLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    // Mock MetricRecorder
    MetricRecorder mockMetricRecorder = Mockito.mock(MetricRecorder.class);
    Filter.FilterContext customContext = Filter.FilterContext.create(
        "envoy.filters.http.ext_proc", mockMetricRecorder);

    ExternalProcessorServerInterceptor interceptor = new ExternalProcessorServerInterceptor(
        filterConfig, channelManager, customContext);

    // Custom interceptor to send headers early
    ServerInterceptor failingInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        call.sendHeaders(new Metadata());
        return next.startCall(call, headers);
      }
    };

    dataPlaneHandler = new DataPlaneServiceHandler() {
      @Override
      public StreamObserver<InputStream> sayHelloBidi(
          final StreamObserver<InputStream> responseObserver) {
        return new StreamObserver<InputStream>() {
          @Override
          public void onNext(InputStream value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());
          }
        };
      }
    };

    // Start data plane with both interceptors
    startDataPlane(failingInterceptor, interceptor);

    ClientCall<InputStream, InputStream> clientCall =
        dataPlaneChannel.newCall(METHOD_SAY_HELLO_BIDI, io.grpc.CallOptions.DEFAULT);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> callStatus = new AtomicReference<>();
    clientCall.start(new ClientCall.Listener<InputStream>() {
      @Override
      public void onMessage(InputStream message) {}

      @Override
      public void onClose(Status status, Metadata trailers) {
        callStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());

    clientCall.request(1);
    clientCall.sendMessage(new ByteArrayInputStream("msg".getBytes(StandardCharsets.UTF_8)));
    clientCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

    // Verify metrics
    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.clientHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.clientHalfCloseDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.serverHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorServerInterceptor.serverTrailersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of()),
        Mockito.eq(com.google.common.collect.ImmutableList.of()));

    channelManager.close();
  }
  private void startDummyExtProcServer() throws IOException {
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {}
              @Override
              public void onError(Throwable t) {}
              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(
        InProcessServerBuilder.forName(extProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());
  }
}
