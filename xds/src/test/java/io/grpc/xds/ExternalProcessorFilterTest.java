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
import io.grpc.internal.SerializingExecutor;
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
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    AtomicReference<Executor> capturedExecutor = new AtomicReference<>();
    ClientInterceptor sidecarInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        capturedExecutor.set(callOptions.getExecutor());
        return next.newCall(method, callOptions);
      }
    };

    ManagedChannel sidecarChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(extProcServerName)
            .directExecutor()
            .intercept(sidecarInterceptor)
            .build());

    CachedChannelManager channelManager = new CachedChannelManager(config -> sidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    Executor callExecutor = command -> {};
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(callExecutor);

    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(ServerServiceDefinition.builder(ExternalProcessorGrpc.SERVICE_NAME)
            .addMethod(ExternalProcessorGrpc.getProcessMethod(), ServerCalls.asyncBidiStreamingCall(
                (responseObserver) -> new StreamObserver<ProcessingRequest>() {
                  @Override public void onNext(ProcessingRequest value) {}
                  @Override public void onError(Throwable t) {}
                  @Override public void onCompleted() { responseObserver.onCompleted(); }
                }))
            .build())
        .directExecutor()
        .build().start());

    Channel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(interceptor)
            .build());

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ClientCall<String, String> proxyCall = dataPlaneChannel.newCall(METHOD_SAY_HELLO, callOptions);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedExecutor.get()).isInstanceOf(SerializingExecutor.class);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithTimeout_whenCallIntercepted_thenExtProcStubHasCorrectDeadline() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .setTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(5).build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Verify sidecar call has correct deadline
    ArgumentCaptor<CallOptions> sidecarOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
    Mockito.verify(mockSidecarChannel).newCall(
        Mockito.eq(ExternalProcessorGrpc.getProcessMethod()), 
        sidecarOptionsCaptor.capture());

    Deadline deadline = sidecarOptionsCaptor.getValue().getDeadline();
    assertThat(deadline).isNotNull();
    assertThat(deadline.timeRemaining(TimeUnit.SECONDS)).isAtLeast(4);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithInitialMetadata_whenCallIntercepted_thenExtProcStreamSendsMetadata() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
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
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Verify sidecar stream started with initial metadata
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    Mockito.verify(mockSidecarCall).start(Mockito.any(), metadataCaptor.capture());

    Metadata captured = metadataCaptor.getValue();
    assertThat(captured.get(Metadata.Key.of("x-init-key", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("init-val");
    assertThat(captured.get(Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER))).isEqualTo(new byte[]{1, 2, 3});
  }

  // --- Category 3: Request Header Processing ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenStartCalled_thenExtProcReceivesHeadersAndCallIsBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Verify headers sent to sidecar
    ArgumentCaptor<ProcessingRequest> requestCaptor = ArgumentCaptor.forClass(ProcessingRequest.class);
    Mockito.verify(mockSidecarCall).sendMessage(requestCaptor.capture());
    assertThat(requestCaptor.getValue().hasRequestHeaders()).isTrue();

    // Verify main call NOT yet started
    Mockito.verify(mockRawCall, Mockito.never()).start(Mockito.any(), Mockito.any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenExtProcRespondsWithMutations_thenMutationsAreAppliedAndCallIsActivated() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), headers);

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());
    
    // Simulate sidecar response with header mutation
    ProcessingResponse resp = ProcessingResponse.newBuilder()
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
        .build();
    
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify mutations applied and call started
    assertThat(headers.get(Metadata.Key.of("x-mutated", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    Mockito.verify(mockRawCall).start(Mockito.any(), Mockito.eq(headers));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSkip_whenStartCalled_thenDataPlaneCallIsActivatedImmediately() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), headers);

    // Verify main call started immediately
    Mockito.verify(mockRawCall).start(Mockito.any(), Mockito.eq(headers));
    
    // Verify sidecar NOT messaged about headers
    Mockito.verify(mockSidecarCall, Mockito.never()).sendMessage(Mockito.any());
  }

  // --- Category 4: Body Mutation: Outbound/Request (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenSendMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    proxyCall.sendMessage("Body Message");

    ArgumentCaptor<ProcessingRequest> requestCaptor = ArgumentCaptor.forClass(ProcessingRequest.class);
    Mockito.verify(mockSidecarCall).sendMessage(requestCaptor.capture());
    assertThat(requestCaptor.getValue().hasRequestBody()).isTrue();
    assertThat(requestCaptor.getValue().getRequestBody().getBody().toStringUtf8()).isEqualTo("Body Message");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyIsForwardedToDataPlane() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    proxyCall.sendMessage("Original");

    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setRequestBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setBody(ByteString.copyFromUtf8("Mutated"))
                        .build())
                    .build())
                .build())
            .build())
        .build();
    
    sidecarListenerCaptor.getValue().onMessage(resp);

    ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
    Mockito.verify(mockRawCall).sendMessage(bodyCaptor.capture());
    assertThat(new String(com.google.common.io.ByteStreams.toByteArray(bodyCaptor.getValue()), StandardCharsets.UTF_8)).isEqualTo("Mutated");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcSignaledEndOfStream_whenClientSendsMoreMessages_thenMessagesAreDiscarded() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setRequestBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setEndOfStream(true)
                        .build())
                    .build())
                .build())
            .build())
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    Mockito.verify(mockRawCall).halfClose();

    proxyCall.sendMessage("Too late");

    // Verify sidecar and raw call NOT messaged after EOS
    Mockito.verify(mockSidecarCall, Mockito.times(0)).sendMessage(Mockito.any());
    Mockito.verify(mockRawCall, Mockito.times(0)).sendMessage(Mockito.any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenHalfCloseCalled_thenSignalSentToExtProcAndSuperHalfCloseIsDeferred() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    proxyCall.halfClose();

    ArgumentCaptor<ProcessingRequest> requestCaptor = ArgumentCaptor.forClass(ProcessingRequest.class);
    Mockito.verify(mockSidecarCall).sendMessage(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getRequestBody().getEndOfStreamWithoutMessage()).isTrue();

    // Verify super.halfClose() was deferred
    Mockito.verify(mockRawCall, Mockito.never()).halfClose();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDeferredHalfClose_whenExtProcRespondsWithEndOfStream_thenSuperHalfCloseIsCalled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    proxyCall.halfClose();

    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setRequestBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setEndOfStreamWithoutMessage(true)
                        .build())
                    .build())
                .build())
            .build())
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify super.halfClose() called after sidecar EOS
    Mockito.verify(mockRawCall).halfClose();
  }

  // --- Category 5: Body Mutation: Inbound/Response (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenOnMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
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

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    
    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());

    rawListenerCaptor.getValue().onMessage(new ByteArrayInputStream("Server Message".getBytes(StandardCharsets.UTF_8)));

    ArgumentCaptor<ProcessingRequest> requestCaptor = ArgumentCaptor.forClass(ProcessingRequest.class);
    Mockito.verify(mockSidecarCall).sendMessage(requestCaptor.capture());
    assertThat(requestCaptor.getValue().hasResponseBody()).isTrue();
    assertThat(requestCaptor.getValue().getResponseBody().getBody().toStringUtf8()).isEqualTo("Server Message");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyIsDeliveredToClient() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
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

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());
    
    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    rawListenerCaptor.getValue().onMessage(new ByteArrayInputStream("Original".getBytes(StandardCharsets.UTF_8)));

    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setResponseBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setBody(ByteString.copyFromUtf8("Mutated Server"))
                        .build())
                    .build())
                .build())
            .build())
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    Mockito.verify(mockAppListener).onMessage("Mutated Server");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenExtProcRespondsWithEndOfStream_thenClientListenerCloseIsPropagated() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
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

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());
    
    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    rawListenerCaptor.getValue().onClose(Status.OK, new Metadata());

    // Verify app listener NOT closed yet (waiting for sidecar EOS)
    Mockito.verify(mockAppListener, Mockito.never()).onClose(Mockito.any(), Mockito.any());

    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setResponseBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setEndOfStreamWithoutMessage(true)
                        .build())
                    .build())
                .build())
            .build())
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify app listener notified with trailers
    Mockito.verify(mockAppListener).onClose(Mockito.eq(Status.OK), Mockito.any());
  }

  // --- Category 6: Outbound Backpressure (isReady / onReady) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeTrue_whenExtProcBusy_thenIsReadyReturnsFalse() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());
    
    // Simulate sidecar is busy
    Mockito.when(mockSidecarCall.isReady()).thenReturn(false);

    assertThat(proxyCall.isReady()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenIsReadyReturnsTrue() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(false)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Sidecar is busy
    Mockito.when(mockSidecarCall.isReady()).thenReturn(false);

    // Should still be ready because observability_mode is false
    assertThat(proxyCall.isReady()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenIsReadyCalled_thenReturnsFalse() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Send request_drain: true
    ProcessingResponse resp = ProcessingResponse.newBuilder().setRequestDrain(true).build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    // isReady() must return false during drain
    assertThat(proxyCall.isReady()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenCongestionInExtProc_whenExtProcBecomesReady_thenTriggersOnReady() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);
    Mockito.when(mockSidecarCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());
    
    // Trigger sidecar onReady
    sidecarListenerCaptor.getValue().onReady();

    // Verify app listener notified
    Mockito.verify(mockAppListener).onReady();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenTriggersOnReady() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);
    Mockito.when(mockSidecarCall.isReady()).thenReturn(true);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Enter drain
    sidecarListenerCaptor.getValue().onMessage(ProcessingResponse.newBuilder().setRequestDrain(true).build());
    assertThat(proxyCall.isReady()).isFalse();

    // Sidecar stream completes
    sidecarListenerCaptor.getValue().onClose(Status.OK, new Metadata());

    // Verify app listener notified to resume flow
    Mockito.verify(mockAppListener).onReady();
    assertThat(proxyCall.isReady()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenMessagesProceedWithoutModification() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
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
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // 1. Sidecar initiates drain
    sidecarListenerCaptor.getValue().onMessage(ProcessingResponse.newBuilder().setRequestDrain(true).build());
    
    // 2. Sidecar closes stream with OK status
    sidecarListenerCaptor.getValue().onClose(Status.OK, new Metadata());

    // 3. Verify application message is forwarded to data plane WITHOUT sidecar call
    proxyCall.sendMessage("Direct Message");
    ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
    Mockito.verify(mockRawCall).sendMessage(bodyCaptor.capture());
    assertThat(new String(com.google.common.io.ByteStreams.toByteArray(bodyCaptor.getValue()), StandardCharsets.UTF_8)).isEqualTo("Direct Message");
    
    // Sidecar should NOT have received a requestBody message
    Mockito.verify(mockSidecarCall, Mockito.never()).sendMessage(Mockito.argThat(req -> req.hasRequestBody()));

    // 4. Verify server response is delivered to application WITHOUT sidecar call
    rawListenerCaptor.getValue().onMessage(new ByteArrayInputStream("Direct Response".getBytes(StandardCharsets.UTF_8)));
    Mockito.verify(mockAppListener).onMessage("Direct Response");

    // Sidecar should NOT have received a responseBody message
    Mockito.verify(mockSidecarCall, Mockito.never()).sendMessage(Mockito.argThat(req -> req.hasResponseBody()));
  }

  // --- Category 7: Inbound Backpressure (request(n) / pendingRequests) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeTrue_whenExtProcBusy_thenAppRequestsAreBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);
    
    // Sidecar is NOT ready
    Mockito.when(mockSidecarCall.isReady()).thenReturn(false);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    proxyCall.request(5);

    // Verify raw call NOT requested yet
    Mockito.verify(mockRawCall, Mockito.never()).request(Mockito.anyInt());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenAppRequestsAreNOTBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(false)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    
    // Sidecar is NOT ready
    Mockito.when(mockSidecarCall.isReady()).thenReturn(false);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    proxyCall.request(5);

    // Verify raw call requested immediately because obs_mode is false
    Mockito.verify(mockRawCall).request(5);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenAppRequestsMessages_thenRequestsAreBuffered() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Enter drain
    sidecarListenerCaptor.getValue().onMessage(ProcessingResponse.newBuilder().setRequestDrain(true).build());

    proxyCall.request(3);

    // Verify raw call NOT requested during drain
    Mockito.verify(mockRawCall, Mockito.never()).request(Mockito.anyInt());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenBufferedRequests_whenExtProcStreamBecomesReady_thenDataPlaneRequestIsDrained() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);
    Mockito.when(mockRawCall.isReady()).thenReturn(true);
    
    // Start with sidecar NOT ready
    Mockito.when(mockSidecarCall.isReady()).thenReturn(false);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    proxyCall.request(10);
    Mockito.verify(mockRawCall, Mockito.never()).request(Mockito.anyInt());

    // Sidecar becomes ready
    Mockito.when(mockSidecarCall.isReady()).thenReturn(true);
    sidecarListenerCaptor.getValue().onReady();

    // Verify buffered request drained
    Mockito.verify(mockRawCall).request(10);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcStreamCompleted_whenAppRequestsMessages_thenRequestsAreForwardedImmediately() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Sidecar stream completes
    sidecarListenerCaptor.getValue().onClose(Status.OK, new Metadata());

    proxyCall.request(7);

    // Verify requested immediately after sidecar is gone
    Mockito.verify(mockRawCall).request(7);
  }

  // --- Category 8: Error Handling & Security ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowFalse_whenExtProcStreamFails_thenDataPlaneCallIsCancelled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(false) // Fail Closed
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Sidecar stream fails
    sidecarListenerCaptor.getValue().onClose(Status.INTERNAL.withDescription("Sidecar Error"), new Metadata());

    // Verify raw call cancelled
    Mockito.verify(mockRawCall).cancel(Mockito.contains("External processor stream failed"), Mockito.any());

    // Simulate raw call closure due to cancellation
    rawListenerCaptor.getValue().onClose(Status.CANCELLED.withDescription("Cancelled by sidecar failure"), new Metadata());

    // Verify application receives UNAVAILABLE with correct description as per gRFC A93
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(mockAppListener).onClose(statusCaptor.capture(), Mockito.any());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(statusCaptor.getValue().getDescription()).contains("External processor stream failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowTrue_whenExtProcStreamFails_thenDataPlaneCallFailsOpen() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(true) // Fail Open
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Sidecar stream fails
    sidecarListenerCaptor.getValue().onClose(Status.INTERNAL.withDescription("Sidecar Error"), new Metadata());

    // Verify raw call NOT cancelled
    Mockito.verify(mockRawCall, Mockito.never()).cancel(Mockito.any(), Mockito.any());
    
    // Verify raw call started (failed open)
    Mockito.verify(mockRawCall).start(Mockito.any(), Mockito.any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponse_whenReceived_thenDataPlaneCallIsCancelledWithProvidedStatus() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Simulate sidecar sending ImmediateResponse (e.g., Unauthenticated)
    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setImmediateResponse(ImmediateResponse.newBuilder()
            .setGrpcStatus(io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                .setStatus(Status.UNAUTHENTICATED.getCode().value())
                .build())
            .setDetails("Custom security rejection")
            .build())
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify data plane call cancelled
    Mockito.verify(mockRawCall).cancel(Mockito.contains("Rejected by ExtProc"), Mockito.any());
    
    // Verify app listener notified with the correct status and details
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(mockAppListener).onClose(statusCaptor.capture(), Mockito.any());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("Custom security rejection");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenUnsupportedCompressionInResponse_whenReceived_thenExtProcStreamIsErroredAndCallIsCancelled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // Simulate sidecar sending compressed body mutation (unsupported)
    ProcessingResponse resp = ProcessingResponse.newBuilder()
        .setRequestBody(BodyResponse.newBuilder()
            .setResponse(CommonResponse.newBuilder()
                .setBodyMutation(BodyMutation.newBuilder()
                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                        .setGrpcMessageCompressed(true)
                        .build())
                    .build())
                .build())
            .build())
        .build();
    
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify sidecar stream was errored explicitly (cancelled by client with onError)
    Mockito.verify(mockSidecarCall).cancel(Mockito.anyString(), Mockito.any());
    
    // Verify raw call cancelled
    Mockito.verify(mockRawCall).cancel(Mockito.contains("External processor stream failed"), Mockito.any());

    // Simulate raw call closure due to cancellation
    rawListenerCaptor.getValue().onClose(Status.CANCELLED.withDescription("Cancelled by sidecar failure"), new Metadata());

    // Verify application receives UNAVAILABLE with correct description
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(mockAppListener).onClose(statusCaptor.capture(), Mockito.any());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(statusCaptor.getValue().getDescription()).contains("External processor stream failed");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseInTrailers_whenReceived_thenDataPlaneCallStatusIsOverridden() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ArgumentCaptor<ClientCall.Listener<ProcessingResponse>> sidecarListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ArgumentCaptor<ClientCall.Listener<InputStream>> rawListenerCaptor = ArgumentCaptor.forClass(ClientCall.Listener.class);
    ClientCall.Listener<String> mockAppListener = Mockito.mock(ClientCall.Listener.class);
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(mockAppListener, new Metadata());

    Mockito.verify(mockRawCall).start(rawListenerCaptor.capture(), Mockito.any());
    Mockito.verify(mockSidecarCall).start(sidecarListenerCaptor.capture(), Mockito.any());

    // 1. Activate call immediately (no request headers mode)
    // 2. Data plane call receives trailers
    Metadata originalTrailers = new Metadata();
    rawListenerCaptor.getValue().onClose(Status.OK, originalTrailers);

    // 3. Sidecar receives trailers event
    ArgumentCaptor<ProcessingRequest> requestCaptor = ArgumentCaptor.forClass(ProcessingRequest.class);
    Mockito.verify(mockSidecarCall).sendMessage(requestCaptor.capture());
    assertThat(requestCaptor.getValue().hasResponseTrailers()).isTrue();

    // 4. Sidecar responds with ImmediateResponse overriding status to DATA_LOSS and adding a header
    ProcessingResponse resp = ProcessingResponse.newBuilder()
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
        .build();
    sidecarListenerCaptor.getValue().onMessage(resp);

    // Verify application receives the OVERRIDDEN status and merged trailers
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    ArgumentCaptor<Metadata> trailersCaptor = ArgumentCaptor.forClass(Metadata.class);
    Mockito.verify(mockAppListener).onClose(statusCaptor.capture(), trailersCaptor.capture());
    
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.DATA_LOSS);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("Sidecar detected data loss");
    assertThat(trailersCaptor.getValue().get(Metadata.Key.of("x-sidecar-extra", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    
    // Verify sidecar stream closed
    Mockito.verify(mockSidecarCall).halfClose();
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
                .setTargetUri("in-process:///sidecar")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ManagedChannel mockSidecarChannel = Mockito.mock(ManagedChannel.class);
    ClientCall<ProcessingRequest, ProcessingResponse> mockSidecarCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockSidecarChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockSidecarCall);

    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    Mockito.when(mockChannelManager.getChannel(Mockito.any())).thenReturn(mockSidecarChannel);

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, mockChannelManager, scheduler);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, mockNextChannel);
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    // Application cancels the RPC
    proxyCall.cancel("User cancelled", null);

    // Verify sidecar stream also cancelled
    Mockito.verify(mockSidecarCall).cancel(Mockito.anyString(), Mockito.any());
    
    // Verify data plane call cancelled
    Mockito.verify(mockRawCall).cancel(Mockito.eq("User cancelled"), Mockito.any());
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
