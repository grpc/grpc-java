package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
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
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorInterceptor;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.ChannelCredsConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ExternalProcessorFilter}.
 */
@RunWith(JUnit4.class)
public class ExternalProcessorFilterTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final MutableHandlerRegistry dataPlaneServiceRegistry = new MutableHandlerRegistry();

  private String dataPlaneServerName;
  private String extProcServerName;
  private ScheduledExecutorService scheduler;
  private ExternalProcessorFilter.Provider provider;
  private Filter.FilterContext filterContext;

  // Define a simple test service
  private static final MethodDescriptor<String, String> METHOD_SAY_HELLO =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.TestService/SayHello")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new StringMarshaller())
          .build();

  private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
          buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class InProcessNameResolverProvider extends NameResolverProvider {
    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      if ("in-process".equals(targetUri.getScheme())) {
        return new NameResolver() {
          @Override public String getServiceAuthority() { return "localhost"; }
          @Override public void start(Listener2 listener) {}
          @Override public void shutdown() {}
        };
      }
      return null;
    }
    @Override protected boolean isAvailable() { return true; }
    @Override protected int priority() { return 5; }
    @Override public String getDefaultScheme() { return "in-process"; }
    @Override public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return Collections.emptyList();
    }
  }

  @Before
  public void setUp() throws Exception {
    NameResolverRegistry.getDefaultRegistry().register(new InProcessNameResolverProvider());

    dataPlaneServerName = InProcessServerBuilder.generateName();
    extProcServerName = InProcessServerBuilder.generateName();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    provider = new ExternalProcessorFilter.Provider();

    Bootstrapper.BootstrapInfo bootstrapInfo = Mockito.mock(Bootstrapper.BootstrapInfo.class);
    Mockito.when(bootstrapInfo.node()).thenReturn(Node.newBuilder().build());
    Mockito.when(bootstrapInfo.allowedGrpcServices()).thenReturn(Optional.empty());

    Bootstrapper.ServerInfo serverInfo = Mockito.mock(Bootstrapper.ServerInfo.class);
    Mockito.when(serverInfo.isTrustedXdsServer()).thenReturn(true);
    
    filterContext = Filter.FilterContext.builder()
        .bootstrapInfo(bootstrapInfo)
        .serverInfo(serverInfo)
        .build();

    grpcCleanup.register(InProcessServerBuilder.forName(dataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneServiceRegistry)
        .directExecutor()
        .build().start());
  }

  @After
  public void tearDown() {
    scheduler.shutdownNow();
  }

  // --- Category 1: Configuration Parsing & Provider ---

  @Test
  public void givenValidConfig_whenParsed_thenReturnsFilterConfig() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///test")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isNotNull();
    assertThat(result.config.typeUrl()).isEqualTo(ExternalProcessorFilter.TYPE_URL);
  }

  @Test
  public void givenUnsupportedBodyMode_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED) // Unsupported
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Invalid request_body_mode");
  }

  @Test
  public void givenInvalidGrpcService_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder().build()) // Invalid: no GoogleGrpc
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("GrpcService must have GoogleGrpc");
  }

  // --- Category 2: Client Interceptor & Lifecycle ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenInterceptor_whenCallIntercepted_thenExtProcStubUsesSerializingExecutor() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<Executor> capturedExecutor = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  if (method.equals(ExternalProcessorGrpc.getProcessMethod())) {
                    capturedExecutor.set(callOptions.getExecutor());
                  }
                  return next.newCall(method, callOptions);
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Executor mockExecutor = Mockito.mock(Executor.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(mockExecutor);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    assertThat(capturedExecutor.get()).isNotNull();
    assertThat(capturedExecutor.get().getClass().getName()).contains("SerializingExecutor");
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithTimeout_whenCallIntercepted_thenExtProcStubHasCorrectDeadline() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(5).build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<Deadline> capturedDeadline = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  if (method.equals(ExternalProcessorGrpc.getProcessMethod())) {
                    capturedDeadline.set(callOptions.getDeadline());
                  }
                  return next.newCall(method, callOptions);
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    assertThat(capturedDeadline.get()).isNotNull();
    assertThat(capturedDeadline.get().timeRemaining(TimeUnit.SECONDS)).isAtLeast(4);
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithInitialMetadata_whenCallIntercepted_thenExtProcStreamSendsMetadata() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .addInitialMetadata(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                .setKey("x-init-key").setValue("init-val").build())
            .addInitialMetadata(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                .setKey("x-bin-key-bin").setRawValue(ByteString.copyFrom(new byte[]{1, 2, 3})).build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };

    ServerServiceDefinition interceptedExtProc = ServerInterceptors.intercept(
        extProcImpl,
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        });

    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(interceptedExtProc)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    assertThat(capturedHeaders.get()).isNotNull();
    assertThat(capturedHeaders.get().get(Metadata.Key.of("x-init-key", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("init-val");
    assertThat(capturedHeaders.get().get(Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER)))
        .isEqualTo(new byte[]{1, 2, 3});
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 3: Request Header Processing ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenStartCalled_thenExtProcReceivesHeadersAndCallIsBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch requestSentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            capturedRequest.set(request);
            requestSentLatch.countDown();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    final AtomicBoolean rawCallStarted = new AtomicBoolean(false);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public void start(Listener<RespT> responseListener, Metadata headers) {
                    rawCallStarted.set(true);
                    super.start(responseListener, headers);
                  }
                };
              }
            })
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    assertThat(requestSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().hasRequestHeaders()).isTrue();

    // Verify main call NOT yet started
    assertThat(rawCallStarted.get()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenExtProcRespondsWithMutations_thenMutationsAreAppliedAndCallIsActivated() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setHeaderMutation(HeaderMutation.newBuilder()
                              .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                      .setKey("x-mutated").setValue("true").build())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    final CountDownLatch serverCallLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  responseObserver.onNext("Hello " + request);
                  responseObserver.onCompleted();
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            serverCallLatch.countDown();
            return next.startCall(call, headers);
          }
        }));

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), headers);

    // Verify main call started with mutated headers on server side
    assertThat(serverCallLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Metadata finalHeaders = capturedHeaders.get();
    assertThat(finalHeaders.get(Metadata.Key.of("x-mutated", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSkip_whenStartCalled_thenDataPlaneCallIsActivatedImmediately() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarMessages.incrementAndGet();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    final java.util.concurrent.CountDownLatch dataPlaneLatch = new java.util.concurrent.CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneLatch.countDown();
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.sendMessage("Hello");
    proxyCall.halfClose();

    // Verify main call reached server side immediately
    assertThat(dataPlaneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    // Verify sidecar NOT messaged about headers
    assertThat(sidecarMessages.get()).isEqualTo(0);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 4: Body Mutation: Outbound/Request (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenSendMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch bodySentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody()) {
              capturedRequest.set(request);
              bodySentLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.sendMessage("Hello World");

    assertThat(bodySentLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().getRequestBody().getBody().toStringUtf8()).contains("Hello World");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyIsForwardedToDataPlane() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("Mutated"))
                                  .setEndOfStream(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .build().start());

    final java.util.concurrent.atomic.AtomicReference<String> capturedDataPlaneRequest = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.CountDownLatch dataPlaneLatch = new java.util.concurrent.CountDownLatch(1);
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              capturedDataPlaneRequest.set(request);
              dataPlaneLatch.countDown();
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onMessage(String message) {}
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);

    proxyCall.sendMessage("Original");
    proxyCall.halfClose();

    assertThat(dataPlaneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(capturedDataPlaneRequest.get()).isEqualTo("Mutated");
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcSignaledEndOfStream_whenClientSendsMoreMessages_thenMessagesAreDiscarded() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final java.util.concurrent.atomic.AtomicInteger sidecarMessages = new java.util.concurrent.atomic.AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarMessages.incrementAndGet();
            if (request.hasRequestBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(com.google.protobuf.ByteString.copyFromUtf8("Acknowledged"))
                                  .setEndOfStream(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    final java.util.concurrent.atomic.AtomicInteger dataPlaneMessages = new java.util.concurrent.atomic.AtomicInteger(0);
    final java.util.concurrent.CountDownLatch dataPlaneLatch = new java.util.concurrent.CountDownLatch(1);
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneMessages.incrementAndGet();
              dataPlaneLatch.countDown();
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).build());

    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onMessage(String message) {}
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);

    proxyCall.sendMessage("Trigger EOS");

    // Wait for sidecar EOS and data plane call closure
    assertThat(dataPlaneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneMessages.get()).isEqualTo(1);

    proxyCall.sendMessage("Too late");

    // Verify sidecar and data plane NOT messaged after EOS
    assertThat(sidecarMessages.get()).isEqualTo(1);
    assertThat(dataPlaneMessages.get()).isEqualTo(1);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenHalfCloseCalled_thenSignalSentToExtProcAndSuperHalfCloseIsDeferred() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch halfCloseLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody() && request.getRequestBody().getEndOfStreamWithoutMessage()) {
              halfCloseLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    final java.util.concurrent.atomic.AtomicBoolean dataPlaneHalfClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public void halfClose() {
                    dataPlaneHalfClosed.set(true);
                    super.halfClose();
                  }
                };
              }
            })
            .directExecutor()
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.halfClose();

    // Verify sidecar received end_of_stream_without_message
    assertThat(halfCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    // Verify super.halfClose() is NOT yet called
    assertThat(dataPlaneHalfClosed.get()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDeferredHalfClose_whenExtProcRespondsWithEndOfStream_thenSuperHalfCloseIsCalled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final java.util.concurrent.CountDownLatch responseSentLatch = new java.util.concurrent.CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody() && request.getRequestBody().getEndOfStreamWithoutMessage()) {
              // Respond with end_of_stream
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
              responseSentLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch = new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public void halfClose() {
                    super.halfClose();
                    dataPlaneHalfClosedLatch.countDown();
                  }
                };
              }
            })
            .build());

    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);

    proxyCall.halfClose();

    // Verify sidecar response sent (triggered by halfClose)
    assertThat(responseSentLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    // Verify super.halfClose() was called after sidecar response
    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    channelManager.close();
  }

  // --- Category 5: Body Mutation: Inbound/Response (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenOnMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final java.util.concurrent.CountDownLatch sidecarCallLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch responseSentLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<ProcessingRequest> capturedRequest = new java.util.concurrent.atomic.AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        sidecarCallLatch.countDown();
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseBody()) {
              boolean isEmpty = request.getResponseBody().getBody().isEmpty();
              boolean isEos = request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setEndOfStream(isEos)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              if (!isEmpty) {
                capturedRequest.set(request);
                responseSentLatch.countDown();
              }
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .build().start());

    final java.util.concurrent.CountDownLatch dataPlaneReceivedLatch = new java.util.concurrent.CountDownLatch(1);
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneReceivedLatch.countDown();
              responseObserver.onNext("Server Message");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onMessage(String message) {}
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Body");
    proxyCall.halfClose();

    // Verify data plane reached
    assertThat(dataPlaneReceivedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Verify sidecar call established
    assertThat(sidecarCallLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Verify sidecar received response body from data plane
    assertThat(responseSentLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().hasResponseBody()).isTrue();
    assertThat(capturedRequest.get().getResponseBody().getBody().toStringUtf8()).isEqualTo("Server Message");
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyIsDeliveredToClient() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    // External Processor Server
    final java.util.concurrent.CountDownLatch sidecarCallLatch = new java.util.concurrent.CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        sidecarCallLatch.countDown();
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseBody()) {
              boolean isEos = request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage();
              boolean isEmpty = request.getResponseBody().getBody().isEmpty();
              
              if (!isEmpty) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setResponseBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setBody(com.google.protobuf.ByteString.copyFromUtf8("Mutated Response"))
                                    .setEndOfStream(isEos)
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
              } else if (isEos) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setResponseBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setEndOfStream(true)
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
              }
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .build().start());
    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Original");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final java.util.concurrent.CountDownLatch appMessageLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<String> capturedAppResponse = new java.util.concurrent.atomic.AtomicReference<>();
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onMessage(String message) {
        capturedAppResponse.set(message);
        appMessageLatch.countDown();
      }
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Hello");
    proxyCall.halfClose();

    // Verify app listener received mutated response
    assertThat(sidecarCallLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(appMessageLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(capturedAppResponse.get()).isEqualTo("Mutated Response");
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenExtProcRespondsWithEndOfStream_thenClientListenerCloseIsPropagated() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final java.util.concurrent.CountDownLatch sidecarCallLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch sidecarEosLatch = new java.util.concurrent.CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        sidecarCallLatch.countDown();
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseBody() && request.getResponseBody().getEndOfStream()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setEndOfStream(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              sidecarEosLatch.countDown();
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    final java.util.concurrent.CountDownLatch dataPlaneServerLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<StreamObserver<String>> dataPlaneResponseObserver = new java.util.concurrent.atomic.AtomicReference<>();
    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());
    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneResponseObserver.set(responseObserver);
              dataPlaneServerLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Trigger");
    proxyCall.halfClose();

    assertThat(dataPlaneServerLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarCallLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    // Original call closes on server side
    dataPlaneResponseObserver.get().onNext("Response");
    dataPlaneResponseObserver.get().onCompleted();

    // Sidecar responds with EOS
    assertThat(sidecarEosLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Verify app listener notified
    assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 6: Outbound Backpressure (isReady / onReady) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeTrue_whenExtProcBusy_thenIsReadyReturnsFalse() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get() && super.isReady();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Initially ready
    sidecarReady.set(true);
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenIsReadyReturnsTrue() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(false)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get() && super.isReady();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Initially ready
    sidecarReady.set(true);
    
    // Wait for activation (header response)
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy
    sidecarReady.set(false);
    
    // Should still be ready because observability_mode is false
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenIsReadyCalled_thenReturnsFalse() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch drainLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestDrain(true)
                  .build());
              responseObserver.onCompleted();
              drainLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // isReady() must return false during drain
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenCongestionInExtProc_whenExtProcBecomesReady_thenTriggersOnReady() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
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

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final java.util.concurrent.CountDownLatch onReadyLatch = new java.util.concurrent.CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onReady() {
        onReadyLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);

    // Wait for sidecar call to start and listener to be captured
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(sidecarListenerRef.get()).isNotNull();

    // Trigger sidecar onReady
    sidecarListenerRef.get().onReady();

    // Verify app listener notified
    assertThat(onReadyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenTriggersOnReady() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestDrain(true)
                  .build());
              // Server closes stream after sending drain
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    // Wait for sidecar stream completion
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isFalse();

    // After sidecar stream completes, it should trigger onReady and become ready
    Mockito.verify(mockAppListener, Mockito.timeout(5000)).onReady();
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenMessagesProceedWithoutModification() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestDrain(true)
                  .build());
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(rawListenerCaptor.capture(), Mockito.any());

    // Wait for drain and completion
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // 1. Verify application message is forwarded to data plane WITHOUT sidecar contact
    proxyCall.sendMessage("Direct Message");
    ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).sendMessage(bodyCaptor.capture());
    assertThat(new String(com.google.common.io.ByteStreams.toByteArray(bodyCaptor.getValue()), StandardCharsets.UTF_8)).isEqualTo("Direct Message");
    
    // 2. Verify server response is delivered to application WITHOUT sidecar call
    rawListenerCaptor.getValue().onMessage(new ByteArrayInputStream("Direct Response".getBytes(StandardCharsets.UTF_8)));
    Mockito.verify(mockAppListener, Mockito.timeout(5000)).onMessage("Direct Response");
    
    proxyCall.cancel("Cleanup", null);
  }

  // --- Category 7: Inbound Backpressure (request(n) / pendingRequests) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeTrue_whenExtProcBusy_thenAppRequestsAreBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      sidecarListenerRef.set((Listener<ProcessingResponse>) responseListener);
                      super.start(responseListener, headers);
                    }
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get() && super.isReady();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    final java.util.concurrent.atomic.AtomicInteger dataPlaneRequested = new java.util.concurrent.atomic.AtomicInteger(0);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public void request(int numMessages) {
                    dataPlaneRequested.addAndGet(numMessages);
                    super.request(numMessages);
                  }
                };
              }
            })
            .directExecutor()
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for sidecar call to start
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(sidecarListenerRef.get()).isNotNull();

    // Sidecar is busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();

    proxyCall.request(5);

    // Verify raw call NOT requested yet
    assertThat(dataPlaneRequested.get()).isEqualTo(0);

    // Sidecar becomes ready
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();

    // Verify pending requests drained to data plane
    // Wait for async processing
    startTime = System.currentTimeMillis();
    while (dataPlaneRequested.get() < 5 && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(dataPlaneRequested.get()).isEqualTo(5);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenAppRequestsAreNOTBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(false)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      sidecarListenerRef.set((Listener<ProcessingResponse>) responseListener);
                      super.start(responseListener, headers);
                    }
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get() && super.isReady();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for sidecar call to start
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(sidecarListenerRef.get()).isNotNull();

    // Sidecar is busy
    sidecarReady.set(false);
    
    // Wait for activation (header response)
    startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // observability_mode is false, so it should still be ready
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.request(5);

    // Verify raw call requested immediately
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).request(5);
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenAppRequestsMessages_thenRequestsAreBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestDrain(true)
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for drain to be processed
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isFalse();

    // App requests more messages
    proxyCall.request(3);

    // Verify raw call NOT requested during drain
    Mockito.verify(mockRawCall, Mockito.never()).request(Mockito.anyInt());
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenBufferedRequests_whenExtProcStreamBecomesReady_thenDataPlaneRequestIsDrained() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      sidecarListenerRef.set((Listener<ProcessingResponse>) responseListener);
                      super.start(responseListener, headers);
                    }
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get() && super.isReady();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for sidecar call to start
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(sidecarListenerRef.get()).isNotNull();

    // Sidecar is busy initially
    sidecarReady.set(false);
    
    // Request from application
    proxyCall.request(10);

    // Verify rawCall NOT yet requested
    Mockito.verify(mockRawCall, Mockito.never()).request(Mockito.anyInt());

    // Sidecar becomes ready
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();

    // Verify buffered request drained
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).request(10);
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcStreamCompleted_whenAppRequestsMessages_thenRequestsAreForwardedImmediately() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Immediately complete the stream from server side
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for sidecar stream completion
    Mockito.when(mockRawCall.isReady()).thenReturn(true);
    
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.request(7);

    // Verify requested immediately after sidecar is gone
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).request(7);
    
    proxyCall.cancel("Cleanup", null);
  }

  // --- Category 8: Error Handling & Security ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowFalse_whenExtProcStreamFails_thenDataPlaneCallIsCancelled() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(false) // Fail Closed
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        // Immediately fail the stream
        responseObserver.onError(Status.INTERNAL.withDescription("Sidecar Error").asRuntimeException());
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).build());

    final java.util.concurrent.CountDownLatch appCloseLatch = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<Status> capturedStatus = new java.util.concurrent.atomic.AtomicReference<>();
    
    java.util.concurrent.ExecutorService callExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(callExecutor);
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) {
          capturedStatus.set(status);
          appCloseLatch.countDown();
        }
      }, new Metadata());

      // Verify application receives UNAVAILABLE due to sidecar failure
      assertThat(appCloseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(capturedStatus.get().getDescription()).contains("External processor stream failed");
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      callExecutor.shutdownNow();
    }
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowTrue_whenExtProcStreamFails_thenDataPlaneCallFailsOpen() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(true) // Fail Open
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onError(Status.INTERNAL.asRuntimeException());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Verify raw call started (failed open)
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(Mockito.any(), Mockito.any());
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponse_whenReceived_thenDataPlaneCallIsCancelledWithProvidedStatus() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setImmediateResponse(ImmediateResponse.newBuilder()
                      .setGrpcStatus(io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                          .setStatus(Status.UNAUTHENTICATED.getCode().value())
                          .build())
                      .setDetails("Custom security rejection")
                      .build())
                  .build());
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    // Verify data plane call cancelled with the status details
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).cancel(Mockito.eq("Custom security rejection"), Mockito.any());
    
    // Verify app listener notified with the correct status and details
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(mockAppListener, Mockito.timeout(5000)).onClose(statusCaptor.capture(), Mockito.any());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("Custom security rejection");
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenUnsupportedCompressionInResponse_whenReceived_thenExtProcStreamIsErroredAndCallIsCancelled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasRequestBody()) {
              // Simulate sidecar sending compressed body mutation (unsupported)
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setGrpcMessageCompressed(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(rawListenerCaptor.capture(), Mockito.any());

    // Trigger request body processing to hit the unsupported compression check
    proxyCall.request(1);
    proxyCall.sendMessage("test");

    // Verify data plane call cancelled
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).cancel(Mockito.contains("External processor stream failed"), Mockito.any());

    // Simulate raw call closure resulting from cancellation
    rawListenerCaptor.getValue().onClose(Status.CANCELLED, new Metadata());

    // Verify application receives UNAVAILABLE with correct description
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(mockAppListener, Mockito.timeout(5000)).onClose(statusCaptor.capture(), Mockito.any());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(statusCaptor.getValue().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseInTrailers_whenReceived_thenDataPlaneCallStatusIsOverridden() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasResponseTrailers()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setImmediateResponse(ImmediateResponse.newBuilder()
                      .setGrpcStatus(io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                          .setStatus(Status.DATA_LOSS.getCode().value())
                          .build())
                      .setDetails("Sidecar detected data loss")
                      .setHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation.newBuilder()
                          .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                              .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                  .setKey("x-sidecar-extra").setValue("true").build())
                              .build())
                          .build())
                      .build())
                  .build());
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(rawListenerCaptor.capture(), Mockito.any());

    // Original call closes with trailers
    Metadata originalTrailers = new Metadata();
    rawListenerCaptor.getValue().onClose(Status.OK, originalTrailers);

    // Verify application receives the OVERRIDDEN status and merged trailers
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    ArgumentCaptor<Metadata> trailersCaptor = ArgumentCaptor.forClass(Metadata.class);
    Mockito.verify(mockAppListener, Mockito.timeout(5000)).onClose(statusCaptor.capture(), trailersCaptor.capture());
    
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.DATA_LOSS);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("Sidecar detected data loss");
    assertThat(trailersCaptor.getValue().get(Metadata.Key.of("x-sidecar-extra", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
  }

  // --- Category 10: Processing Mode Override ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenAllowOverrideFalse_whenOverrideReceived_thenIgnored() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(false)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<ProcessingRequest> lastBodyRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
                  .build());
            } else if (request.hasRequestBody()) {
              lastBodyRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for activation
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(Mockito.any(), Mockito.any());

    // App sends message
    proxyCall.sendMessage("Message");

    // Message should still be intercepted (sent to sidecar) because override was ignored
    long startTime = System.currentTimeMillis();
    while (lastBodyRequest.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(lastBodyRequest.get()).isNotNull();
    assertThat(lastBodyRequest.get().hasRequestBody()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenAllowedModesSet_whenMismatchOverrideReceived_thenIgnored() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .addAllowedOverrideModes(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<ProcessingRequest> lastBodyRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Send mismatch override (Request Trailers SEND is NOT in allowed list)
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                      .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SEND).build())
                  .build());
            } else if (request.hasRequestBody()) {
              lastBodyRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for activation
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(Mockito.any(), Mockito.any());

    // App sends message
    proxyCall.sendMessage("Message");

    // Message should still be intercepted because override was mismatched
    long startTime = System.currentTimeMillis();
    while (lastBodyRequest.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(lastBodyRequest.get()).isNotNull();
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenOverrideToNone_thenSubsequentMessagesSentDirectly() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
                  .build());
            } else if (request.hasRequestBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for activation
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(Mockito.any(), Mockito.any());

    // Send second message - should go directly to rawCall because override took effect
    proxyCall.sendMessage("Direct");
    ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).sendMessage(bodyCaptor.capture());
    assertThat(new String(com.google.common.io.ByteStreams.toByteArray(bodyCaptor.getValue()), StandardCharsets.UTF_8)).isEqualTo("Direct");
    
    proxyCall.cancel("Cleanup", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeNone_whenOverrideToGrpc_thenSubsequentMessagesInteractedWithSidecar() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<ProcessingRequest> capturedBodyReq = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                      .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                      .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                      .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                      .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                      .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                      .build())
                  .build());
            } else if (request.hasRequestBody()) {
              if (!request.getRequestBody().getBody().isEmpty()) {
                capturedBodyReq.set(request);
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(BodyResponse.newBuilder().build())
                    .build());
              }
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    // Use direct executor to simplify tests
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // 2. App sends message - should now be intercepted
    proxyCall.sendMessage("Original Request Body");
    proxyCall.halfClose();
    
    // Verify intercepted by sidecar
    long startTime = System.currentTimeMillis();
    while (capturedBodyReq.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(capturedBodyReq.get()).isNotNull();
    assertThat(capturedBodyReq.get().getRequestBody().getBody().toStringUtf8()).isEqualTo("Original Request Body");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeNone_whenOverrideToGrpc_thenSubsequentResponsesInteractedWithSidecar() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<ProcessingRequest> capturedRespBodyReq = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                      .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                      .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                      .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                      .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                      .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                      .build())
                  .build());
            } else if (request.hasResponseBody()) {
              capturedRespBodyReq.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    // Use direct executor to simplify tests
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(com.google.common.util.concurrent.MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    // Wait for activation
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(rawListenerCaptor.capture(), Mockito.any());

    // 5. Data plane receives message - should now be intercepted
    rawListenerCaptor.getValue().onMessage(new ByteArrayInputStream("Original Response Body".getBytes(StandardCharsets.UTF_8)));

    // Verify intercepted by sidecar
    long startTime = System.currentTimeMillis();
    while (capturedRespBodyReq.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(capturedRespBodyReq.get()).isNotNull();
    assertThat(capturedRespBodyReq.get().getResponseBody().getBody().toStringUtf8()).isEqualTo("Original Response Body");
    
    proxyCall.cancel("Cleanup", null);
  }

  // --- Category 9: Resource Management ---

  @Test
  public void givenFilter_whenClosed_thenCachedChannelManagerIsClosed() throws Exception {
    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    
    ExternalProcessorFilter filter = new ExternalProcessorFilter("test", mockChannelManager);
    
    filter.close();
    
    Mockito.verify(mockChannelManager).close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenActiveRpc_whenDataPlaneCallCancelled_thenExtProcStreamIsErrored() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch cancelLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) { cancelLatch.countDown(); }
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Wait for activation
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).start(Mockito.any(), Mockito.any());

    // Application cancels the RPC
    proxyCall.cancel("User cancelled", null);

    // Verify sidecar stream also cancelled
    assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Verify data plane call cancelled
    Mockito.verify(mockRawCall, Mockito.timeout(5000)).cancel(Mockito.eq("User cancelled"), Mockito.any());
  }

  @Test
  public void requestHeadersMutated() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager testChannelManager = new CachedChannelManager(config ->
        grpcCleanup.register(InProcessChannelBuilder.forName(extProcServerName).directExecutor().build())
    );

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, testChannelManager, scheduler);

    Channel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(interceptor)
            .build());

    AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build();

    ServerServiceDefinition interceptedServiceDef = ServerInterceptors.intercept(
        serviceDef,
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        });
        
    dataPlaneServiceRegistry.addService(interceptedServiceDef);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setHeaderMutation(HeaderMutation.newBuilder()
                              .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                      .setKey("x-custom-header")
                                      .setValue("custom-value")
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasRequestBody()) {
               if (request.getRequestBody().getEndOfStreamWithoutMessage() || request.getRequestBody().getEndOfStream()) {
                 responseObserver.onNext(ProcessingResponse.newBuilder()
                     .setRequestBody(BodyResponse.newBuilder()
                         .setResponse(CommonResponse.newBuilder()
                             .setBodyMutation(BodyMutation.newBuilder()
                                 .setStreamedResponse(io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse.newBuilder()
                                     .setEndOfStreamWithoutMessage(true)
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
                              .setStreamedResponse(io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse.newBuilder()
                                  .setBody(request.getRequestBody().getBody())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasResponseHeaders()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasResponseBody()) {
               if (request.getResponseBody().getEndOfStream()) {
                 responseObserver.onNext(ProcessingResponse.newBuilder()
                     .setResponseBody(BodyResponse.newBuilder()
                         .setResponse(CommonResponse.newBuilder()
                             .setBodyMutation(BodyMutation.newBuilder()
                                 .setStreamedResponse(io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse.newBuilder()
                                     .setEndOfStreamWithoutMessage(true)
                                     .build())
                                 .build())
                             .build())
                         .build())
                     .build());
                 return;
               }

               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse.newBuilder()
                                  .setBody(request.getResponseBody().getBody())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasResponseTrailers()) {
               responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder()
                      .setHeaderMutation(HeaderMutation.newBuilder().build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    ClientCalls.asyncUnaryCall(dataPlaneChannel.newCall(METHOD_SAY_HELLO, CallOptions.DEFAULT), "World",
        new StreamObserver<String>() {
          @Override public void onNext(String value) { result.set(value); }
          @Override public void onError(Throwable t) { t.printStackTrace(); latch.countDown(); }
          @Override public void onCompleted() { latch.countDown(); }
        });

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(result.get()).isEqualTo("Hello World");
    assertThat(receivedHeaders.get().get(Metadata.Key.of("x-custom-header", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("custom-value");
    
    testChannelManager.close();
  }
}
