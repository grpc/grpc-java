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
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
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
      return new ByteArrayInputStream(value.getBytes());
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
        return new String(buffer.toByteArray());
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

    Executor callExecutor = command -> {}; 
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(callExecutor);

    Channel mockNextChannel = Mockito.mock(Channel.class);
    ClientCall<InputStream, InputStream> mockRawCall = Mockito.mock(ClientCall.class);
    Mockito.when(mockNextChannel.newCall(Mockito.any(MethodDescriptor.class), Mockito.any(CallOptions.class)))
        .thenReturn(mockRawCall);

    ClientCall<String, String> proxyCall = interceptor.interceptCall(
        METHOD_SAY_HELLO, callOptions, mockNextChannel);
    
    proxyCall.start(Mockito.mock(ClientCall.Listener.class), new Metadata());

    ArgumentCaptor<CallOptions> sidecarOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
    Mockito.verify(mockSidecarChannel).newCall(
        Mockito.eq(ExternalProcessorGrpc.getProcessMethod()), 
        sidecarOptionsCaptor.capture());

    assertThat(sidecarOptionsCaptor.getValue().getExecutor()).isSameInstanceAs(callExecutor);
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

    // Data Plane Server
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

    // Ext-Proc Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          private boolean halfClosedByClient = false;

          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              try { Thread.sleep(50); } catch (InterruptedException e) {}
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
                 halfClosedByClient = true;
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
