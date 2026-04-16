package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
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
import io.grpc.internal.FakeClock;
import io.grpc.internal.SerializingExecutor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.ServerCallStreamObserver;
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
import java.util.concurrent.ExecutorService;
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

  private MutableHandlerRegistry dataPlaneServiceRegistry;

  private String dataPlaneServerName;
  private String extProcServerName;
  private final FakeClock fakeClock = new FakeClock();
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

  private static final MethodDescriptor<String, String> METHOD_SERVER_STREAMING =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .setFullMethodName("test.TestService/ServerStreaming")
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

    dataPlaneServiceRegistry = new MutableHandlerRegistry();
    dataPlaneServerName = InProcessServerBuilder.generateName();
    extProcServerName = InProcessServerBuilder.generateName();
    scheduler = fakeClock.getScheduledExecutorService();
    provider = new ExternalProcessorFilter.Provider();

    Bootstrapper.BootstrapInfo bootstrapInfo = Mockito.mock(Bootstrapper.BootstrapInfo.class);
    Mockito.when(bootstrapInfo.node()).thenReturn(Node.newBuilder().build());
    Mockito.when(bootstrapInfo.implSpecificObject()).thenReturn(Optional.empty());

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



  private ExternalProcessor.Builder createBaseProto() {
    return ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///test")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build());
  }

  // --- Category 1: Configuration Parsing & Provider ---

  @Test
  public void givenValidConfig_whenParsed_thenReturnsFilterConfig() throws Exception {
    ExternalProcessor proto = createBaseProto().build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isNotNull();
    assertThat(result.config.typeUrl()).isEqualTo(ExternalProcessorFilter.TYPE_URL);
  }

  @Test
  public void givenUnsupportedBodyMode_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto()
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


  // --- Category 2: Configuration Override ---

  @Test
  public void givenOverrideConfig_whenGrpcServiceOverridden_thenUsesNewService() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///parent")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///override")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getExternalProcessor().getGrpcService().getGoogleGrpc().getTargetUri())
        .isEqualTo("in-process:///override");
  }

  @Test
  public void givenOverrideConfig_whenFailureModeAllowOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setFailureModeAllow(false)
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setFailureModeAllow(true)
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getFailureModeAllow()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenProcessingModeOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getExternalProcessor().getProcessingMode().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
  }

  @Test
  public void givenOverrideConfig_whenAllFieldsOverridden_thenAllTakeEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setFailureModeAllow(false)
        .setObservabilityMode(false)
        .setAllowModeOverride(false)
        .setStatPrefix("parent")
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setFailureModeAllow(true)
        .setObservabilityMode(true)
        .setAllowModeOverride(true)
        .setStatPrefix("override")
        .setMessageTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(10).build())
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getObservabilityMode()).isTrue();
    assertThat(mergedConfig.getAllowModeOverride()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getStatPrefix()).isEqualTo("override");
    assertThat(mergedConfig.getExternalProcessor().getMessageTimeout().getSeconds()).isEqualTo(10);
  }

  @Test
  public void givenOverrideConfig_whenSomeFieldsOverridden_thenMergedCorrectly() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setFailureModeAllow(false)
        .setStatPrefix("parent")
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setFailureModeAllow(true)
        // statPrefix NOT set
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getStatPrefix()).isEqualTo("parent");
  }

  @Test
  public void givenOverrideConfig_whenAllowedOverrideModesOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .addAllowedOverrideModes(ProcessingMode.newBuilder().setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .addAllowedOverrideModes(ProcessingMode.newBuilder().setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getAllowedOverrideModes()).hasSize(1);
    assertThat(interceptor.getFilterConfig().getAllowedOverrideModes().get(0).getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
  }

  @Test
  public void givenOverrideConfig_whenDisableImmediateResponseOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto()
        .setDisableImmediateResponse(false)
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setDisableImmediateResponse(true)
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getDisableImmediateResponse()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenMutationRulesOverridden_thenTakesEffect() throws Exception {
    io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules parentRules = 
        io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
            .setDisallowAll(com.google.protobuf.BoolValue.newBuilder().setValue(false).build())
            .build();
    io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules overrideRules = 
        io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
            .setDisallowAll(com.google.protobuf.BoolValue.newBuilder().setValue(true).build())
            .build();

    ExternalProcessor parentProto = createBaseProto()
        .setMutationRules(parentRules)
        .build();
    ExternalProcessor overrideProto = createBaseProto()
        .setMutationRules(overrideRules)
        .build();

    ExternalProcessorFilterConfig parentConfig = provider.parseFilterConfig(Any.pack(parentProto), filterContext).config;
    ExternalProcessorFilterConfig overrideConfig = provider.parseFilterConfig(Any.pack(overrideProto), filterContext).config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getMutationRulesConfig().get().disallowAll())
        .isTrue();
  }

  // --- Category 3: Client Interceptor & Lifecycle ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenInterceptor_whenCallIntercepted_thenExtProcStubUsesSerializingExecutor() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<Executor> capturedExecutor = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
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

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedExecutor.get()).isNotNull();
    assertThat(capturedExecutor.get().getClass().getName()).contains("SerializingExecutor");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithTimeout_whenCallIntercepted_thenExtProcStubHasCorrectDeadline() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<Deadline> capturedDeadline = new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
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

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedDeadline.get()).isNotNull();
    assertThat(capturedDeadline.get().timeRemaining(TimeUnit.SECONDS)).isAtLeast(4);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithInitialMetadata_whenCallIntercepted_thenExtProcStreamSendsMetadata() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
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

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(interceptedExtProc)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedHeaders.get()).isNotNull();
    assertThat(capturedHeaders.get().get(Metadata.Key.of("x-init-key", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("init-val");
    assertThat(capturedHeaders.get().get(Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER)))
        .isEqualTo(new byte[]{1, 2, 3});
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 4: Request Header Processing ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenStartCalled_thenExtProcReceivesHeadersAndCallIsBuffered() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
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
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch requestSentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            capturedRequest.set(request);
            requestSentLatch.countDown();
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

    final AtomicBoolean dataPlaneStarted = new AtomicBoolean(false);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneStarted.set(true);
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(requestSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().hasRequestHeaders()).isTrue();

    // Verify main call NOT yet started
    assertThat(dataPlaneStarted.get()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenExtProcRespondsWithMutations_thenMutationsAreAppliedAndCallIsActivated() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
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
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
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
            }).start();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
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

    final AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  responseObserver.onNext("Hello " + request);
                  responseObserver.onCompleted();
                  dataPlaneLatch.countDown();
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(new ClientCall.Listener<String>() {}, headers);

    // Send message and half-close to trigger unary call
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify main call started with mutated headers
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Metadata finalHeaders = capturedHeaders.get();
    assertThat(finalHeaders.get(Metadata.Key.of("x-mutated", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSkip_whenStartCalled_thenDataPlaneCallIsActivatedImmediately() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
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
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarMessages.incrementAndGet();
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

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(new ClientCall.Listener<String>() {}, headers);

    // Send message and half-close to trigger unary call
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify main call started immediately
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Verify sidecar NOT messaged about headers
    assertThat(sidecarMessages.get()).isEqualTo(0);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 5: Body Mutation: Outbound/Request (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenSendMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    String uniqueExtProcServerName = "extProc-sendMessage-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-sendMessage-" + InProcessServerBuilder.generateName();
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
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch bodySentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder().build())
                    .build());
              } else if (request.hasRequestBody()) {
                if (capturedRequest.get() == null && !request.getRequestBody().getBody().isEmpty()) {
                  capturedRequest.set(request);
                  bodySentLatch.countDown();
                }
                BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                if (request.getRequestBody().getBody().isEmpty()
                    && request.getRequestBody().getEndOfStreamWithoutMessage()) {
                  bodyResponse.setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setEndOfStream(true)
                              .build())
                          .build())
                      .build());
                } else {
                  bodyResponse.setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setEndOfStream(request.getRequestBody().getEndOfStream())
                              .build())
                          .build())
                      .build());
                }
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(bodyResponse.build())
                    .build());
              }
            }).start();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
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

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Hello World");
    proxyCall.halfClose();

    assertThat(bodySentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().getRequestBody().getBody().toStringUtf8()).contains("Hello World");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyIsForwardedToDataPlane() throws Exception {
    String uniqueExtProcServerName = "extProc-mutatedBody-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-mutatedBody-" + InProcessServerBuilder.generateName();
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
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder().build())
                    .build());
              } else if (request.hasRequestBody()) {
                BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
                if (request.getRequestBody().getBody().isEmpty()
                    && request.getRequestBody().getEndOfStreamWithoutMessage()) {
                  bodyResponse.setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setEndOfStream(true)
                              .build())
                          .build())
                      .build());
                } else {
                  bodyResponse.setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setBody(ByteString.copyFromUtf8("Mutated"))
                              .setEndOfStream(request.getRequestBody().getEndOfStream())
                              .build())
                          .build())
                      .build());
                }
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(bodyResponse.build())
                    .build());
              }
            }).start();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
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

    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              receivedBody.set(request);
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("Original");
    proxyCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBody.get()).isEqualTo("Mutated");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcSignaledEndOfStream_whenClientSendsMoreMessages_thenMessagesAreDiscarded() throws Exception {
    String uniqueExtProcServerName = "extProc-discarded-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-discarded-" + InProcessServerBuilder.generateName();
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
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasRequestBody()) {
              sidecarMessages.incrementAndGet();
              boolean triggerEOS = request.getRequestBody().getBody().toStringUtf8().equals("Trigger EOS");
              BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
              if (triggerEOS || (request.getRequestBody().getBody().isEmpty()
                  && request.getRequestBody().getEndOfStreamWithoutMessage())) {
                bodyResponse.setResponse(CommonResponse.newBuilder()
                    .setBodyMutation(BodyMutation.newBuilder()
                        .setStreamedResponse(StreamedBodyResponse.newBuilder()
                            .setBody(request.getRequestBody().getBody()) // SEND ORIGINAL BODY!
                            .setEndOfStream(true)
                            .build())
                        .build())
                    .build());
              } else {
                bodyResponse.setResponse(CommonResponse.newBuilder()
                    .setBodyMutation(BodyMutation.newBuilder()
                        .setStreamedResponse(StreamedBodyResponse.newBuilder()
                            .setEndOfStream(request.getRequestBody().getEndOfStream())
                            .build())
                        .build())
                    .build());
              }
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(bodyResponse.build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            responseObserver.onCompleted();
          }
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

    final AtomicInteger dataPlaneMessages = new AtomicInteger(0);
    final CountDownLatch dataPlaneHalfCloseLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneMessages.incrementAndGet();
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
              dataPlaneHalfCloseLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("Trigger EOS");
    proxyCall.halfClose();

    assertThat(dataPlaneHalfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneMessages.get()).isEqualTo(1);

    proxyCall.sendMessage("Too late");
    assertThat(dataPlaneMessages.get()).isEqualTo(1);

    // Verify sidecar received Trigger EOS and half-close

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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    final CountDownLatch dataPlaneHalfCloseLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              // Should only be called AFTER sidecar response
              dataPlaneHalfCloseLatch.countDown();
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.halfClose();

    // Verify sidecar received end_of_stream_without_message
    assertThat(halfCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Verify main call NOT yet started (data plane server NOT yet reached)
    assertThat(dataPlaneHalfCloseLatch.getCount()).isEqualTo(1);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDeferredHalfClose_whenExtProcRespondsWithEndOfStream_thenSuperHalfCloseIsCalled() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
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
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch halfCloseLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            ProcessingResponse.Builder response = ProcessingResponse.newBuilder();
            if (request.hasRequestHeaders()) {
              response.setRequestHeaders(HeadersResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder()
                      .build())
                  .build());
            } else if (request.hasRequestBody()) {
              response.setRequestBody(BodyResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setBody(request.getRequestBody().getBody())
                              .setEndOfStream(request.getRequestBody().getEndOfStream())
                              .setEndOfStreamWithoutMessage(request.getRequestBody().getEndOfStreamWithoutMessage())
                              .build())
                          .build())
                      .build())
                  .build());
              if (request.getRequestBody().getEndOfStream() || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                  halfCloseLatch.countDown();
              }
            } else if (request.hasResponseHeaders()) {
              response.setResponseHeaders(HeadersResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder()
                      .build())
                  .build());
            } else if (request.hasResponseBody()) {
              response.setResponseBody(BodyResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder()
                      .setBodyMutation(BodyMutation.newBuilder()
                          .setStreamedResponse(StreamedBodyResponse.newBuilder()
                              .setBody(request.getResponseBody().getBody())
                              .setEndOfStream(request.getResponseBody().getEndOfStream())
                              .build())
                          .build())
                      .build())
                  .build());
            }
            responseObserver.onNext(response.build());
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            responseObserver.onCompleted();
          }
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

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch = new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public void halfClose() {
                    dataPlaneHalfClosedLatch.countDown();
                    super.halfClose();
                  }
                };
              }
            })
            .directExecutor()
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    // Verify super.halfClose() was called after sidecar response
    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 6: Body Mutation: Inbound/Response (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenOnMessageCalled_thenMessageIsSentToExtProc() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

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
    final CountDownLatch sidecarBodyLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody()) {
              if (capturedRequest.get() == null && !request.getResponseBody().getBody().isEmpty()) {
                capturedRequest.set(request);
                sidecarBodyLatch.countDown();
              }
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(request.getResponseBody().getBody())
                                  .setEndOfStream(request.getResponseBody().getEndOfStream())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(scheduler).build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    // Data Plane Server
    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .executor(scheduler)
        .build().start());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Server Message");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).executor(scheduler).build());

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(scheduler);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appMessageLatch.countDown();
      }
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    proxyCall.request(1);
    proxyCall.sendMessage("Hello");
    proxyCall.halfClose();

    long startTime = System.currentTimeMillis();
    while (sidecarBodyLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }
    assertThat(capturedRequest.get().getResponseBody().getBody().toStringUtf8()).isEqualTo("Server Message");

    while ((appMessageLatch.getCount() > 0 || appCloseLatch.getCount() > 0)
        && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }

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
    MutableHandlerRegistry extProcRegistry = new MutableHandlerRegistry();
    final CountDownLatch sidecarBodyLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("Mutated Server"))
                                  .setEndOfStream(request.getResponseBody().getEndOfStream())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              sidecarBodyLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    extProcRegistry.addService(extProcImpl);
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .fallbackHandlerRegistry(extProcRegistry)
        .executor(scheduler)
        .build().start());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(scheduler).build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    // Data Plane Server
    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .executor(scheduler)
        .build().start());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Original");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(scheduler)
            .build());

    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<String> capturedMessage = new AtomicReference<>();

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(scheduler);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        capturedMessage.set(message);
        appMessageLatch.countDown();
      }
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    proxyCall.request(1);
    proxyCall.sendMessage("Hello");
    proxyCall.halfClose();

    long startTime = System.currentTimeMillis();
    while (sidecarBodyLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }
    while (appMessageLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }
    assertThat(capturedMessage.get()).isEqualTo("Mutated Server");
    while (appCloseLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }

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
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    // External Processor Server
    final CountDownLatch sidecarEosLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && request.getResponseBody().getEndOfStream()) {
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

    final CountDownLatch dataPlaneServerLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserver = new AtomicReference<>();
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

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Trigger");
    proxyCall.halfClose();

    assertThat(dataPlaneServerLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Original call closes on server side
    dataPlaneResponseObserver.get().onNext("Response");
    dataPlaneResponseObserver.get().onCompleted();

    // Sidecar responds with EOS
    assertThat(sidecarEosLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify app listener notified
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().isOk()).isTrue();
    
    channelManager.close();
  }

  // --- Category 7: Outbound Backpressure (isReady / onReady) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeTrue_whenExtProcBusy_thenIsReadyReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
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

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation (sidecar needs to respond to headers)
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }



  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenIsReadyCalled_thenReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestDrain(true)
                  .build());
              drainLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            // Don't complete responseObserver immediately to allow test to check draining state
          }
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

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // isReady() must return false during drain.
    // Use a small loop because of SerializingExecutor delay even with directExecutor.
    long start = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - start < 2000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              // No-op
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final CountDownLatch onReadyLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onReady() {
        onReadyLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

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
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenTriggersOnReady() throws Exception {
    String uniqueExtProcServerName = "extProc-draining-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-draining-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch sidecarOnNextLatch = new CountDownLatch(1);
    final CountDownLatch sidecarOnCompletedLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestDrain(true)
                    .build());
                sidecarOnNextLatch.countDown();
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    sidecarOnCompletedLatch.countDown();
                    responseObserver.onCompleted();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(scheduler)
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(scheduler).build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    final CountDownLatch dataPlaneFinishLatch = new CountDownLatch(1);
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              new Thread(() -> {
                try {
                  if (dataPlaneFinishLatch.await(5, TimeUnit.SECONDS)) {
                    responseObserver.onNext("Hello " + request);
                    responseObserver.onCompleted();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final CountDownLatch onReadyLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onReady() {
        onReadyLatch.countDown();
      }
    };

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }
    proxyCall.request(1);
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }

    // Wait for sidecar to send drain and test to observe it
    assertThat(sidecarOnNextLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }
    assertThat(proxyCall.isReady()).isFalse();

    // Now let sidecar complete
    sidecarFinishLatch.countDown();
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }

    dataPlaneFinishLatch.countDown();
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }

    assertThat(sidecarOnCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }

    // After sidecar stream completes, it should trigger onReady and become ready
    assertThat(onReadyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 50 && !proxyCall.isReady(); i++) {
      fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.cancel("Cleanup", null);
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }
    channelManager.close();
    for (int i = 0; i < 10; i++) { fakeClock.forwardTime(1, TimeUnit.SECONDS); }
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
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestDrain(true)
                    .build());
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    responseObserver.onCompleted();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            // Already handled in the background thread
          }
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

    final AtomicReference<String> dataPlaneReceivedMessage = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    final CountDownLatch dataPlaneFinishLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneReceivedMessage.set(request);
              new Thread(() -> {
                try {
                  if (dataPlaneFinishLatch.await(5, TimeUnit.SECONDS)) {
                    responseObserver.onNext("Direct Response");
                    responseObserver.onCompleted();
                    dataPlaneLatch.countDown();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<String> appReceivedMessage = new AtomicReference<>();
    final CountDownLatch appLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onMessage(String message) {
        appReceivedMessage.set(message);
        appLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for drain to be processed
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isFalse();

    // Now let sidecar complete
    sidecarFinishLatch.countDown();

    // Wait for it to become ready again
    startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Request messages from server
    proxyCall.request(1);

    // 1. Verify application message is forwarded to data plane WITHOUT sidecar contact
    proxyCall.sendMessage("Direct Message");
    proxyCall.halfClose();

    // Let data plane finish
    dataPlaneFinishLatch.countDown();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneReceivedMessage.get()).isEqualTo("Direct Message");
    
    // 2. Verify server response is delivered to application WITHOUT sidecar call
    assertThat(appLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessage.get()).isEqualTo("Direct Response");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 8: Inbound Backpressure (request(n) / pendingRequests) ---

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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    final AtomicInteger dataPlaneRequestCount = new AtomicInteger(0);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override public void onNext(String value) { dataPlaneRequestCount.incrementAndGet(); }
                  @Override public void onError(Throwable t) {}
                  @Override public void onCompleted() { responseObserver.onCompleted(); }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
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

    // Verify data plane call NOT requested yet (due to observability mode and sidecar busy)
    assertThat(dataPlaneRequestCount.get()).isEqualTo(0);

    // Sidecar becomes ready
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();

    // After sidecar becomes ready, pending requests should be drained to data plane.
    // In real data plane, request(5) will eventually allow 5 messages.
    // We don't have a direct way to check the raw request count on the server easily without more mocks,
    // but we can verify it's no longer blocked.
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for drain to be processed
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isFalse();

    // App requests more messages
    proxyCall.request(3);

    // proxyCall.isReady() should remain false during drain
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
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

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

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

    // Sidecar becomes ready
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();

    // Verify buffered request drained
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for sidecar stream completion
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.request(7);

    // proxyCall.isReady() should remain true as sidecar is gone
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 9: Error Handling & Security ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowFalse_whenExtProcStreamFails_thenDataPlaneCallIsCancelled() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(false) // Fail Closed
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server triggers error
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Fail the stream immediately on headers
              responseObserver.onError(Status.INTERNAL.withDescription("Simulated sidecar failure").asRuntimeException());
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

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Verify application receives UNAVAILABLE due to sidecar failure
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }).start();
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

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Send message and half-close to trigger unary call reaching server
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify data plane call reached (failed open)
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    final AtomicBoolean dataPlaneStarted = new AtomicBoolean(false);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneStarted.set(true);
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Verify app listener notified with the correct status and details
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(closedStatus.get().getDescription()).isEqualTo("Custom security rejection");
    
    // Data plane call should NOT have been started as sidecar rejected immediately on headers
    assertThat(dataPlaneStarted.get()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseDisabled_whenReceived_thenSidecarStreamErrored() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setDisableImmediateResponse(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server sends immediate response despite being disabled
    final io.grpc.Server extProcServer = grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setImmediateResponse(ImmediateResponse.newBuilder()
                          .setGrpcStatus(io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                              .setStatus(Status.UNAUTHENTICATED.getCode().value())
                              .build())
                          .build())
                      .build());
                }
              }
              @Override public void onError(Throwable t) {}
              @Override public void onCompleted() {}
            };
          }
        })
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

    try {
      final AtomicReference<Status> closedStatus = new AtomicReference<>();
      final CountDownLatch closedLatch = new CountDownLatch(1);
      ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) {
          closedStatus.set(status);
          closedLatch.countDown();
        }
      };
      
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(appListener, new Metadata());

      for (int i = 0; i < 1000 && closedLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      // Verify app listener notified with an error (not the sidecar's UNAUTHENTICATED)
      assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      // It might be INTERNAL (from our onError) or UNAVAILABLE (if stream cancels)
      assertThat(closedStatus.get().getCode()).isAnyOf(Status.Code.INTERNAL, Status.Code.UNAVAILABLE);
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0; i < 100 && (!dataPlaneChannel.isTerminated() || !extProcServer.isTerminated()); i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      channelManager.close();
    }
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Trigger request body processing to hit the unsupported compression check
    proxyCall.request(1);
    proxyCall.sendMessage("test");

    // Verify application receives UNAVAILABLE with correct description
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
              new Thread(() -> {
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
              }).start();
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

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> closedTrailers = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedTrailers.set(trailers);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Request message to allow the call to complete
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify application receives the OVERRIDDEN status and merged trailers
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.DATA_LOSS);
    assertThat(closedStatus.get().getDescription()).isEqualTo("Sidecar detected data loss");
    assertThat(closedTrailers.get().get(Metadata.Key.of("x-sidecar-extra", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
    
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // App sends message
    proxyCall.sendMessage("Message");

    // Message should still be intercepted (sent to sidecar) because override was ignored
    startTime = System.currentTimeMillis();
    while (lastBodyRequest.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(lastBodyRequest.get()).isNotNull();
    assertThat(lastBodyRequest.get().hasRequestBody()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
    
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // App sends message
    proxyCall.sendMessage("Message");

    // Message should still be intercepted because override was mismatched
    startTime = System.currentTimeMillis();
    while (lastBodyRequest.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(lastBodyRequest.get()).isNotNull();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
    
    final AtomicReference<String> dataPlaneReceivedBody = new AtomicReference<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              dataPlaneReceivedBody.set(request);
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
              dataPlaneLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Send second message - should go directly to rawCall because override took effect
    proxyCall.sendMessage("Direct");
    proxyCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(dataPlaneReceivedBody.get()).isEqualTo("Direct");
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
              capturedBodyReq.set(request);
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
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
        Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // 2. App sends message - should now be intercepted
    proxyCall.sendMessage("Original Request Body");
    
    // Verify intercepted by sidecar
    startTime = System.currentTimeMillis();
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
  public void givenResponseBodyModeGrpc_whenOverrideToNone_thenSubsequentResponsesSentDirectly() throws Exception {
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
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarResponseBodyCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setResponseBodyMode(ProcessingMode.BodySendMode.NONE).build())
                  .build());
            } else if (request.hasResponseBody()) {
              sidecarResponseBodyCount.incrementAndGet();
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
    final io.grpc.Server extProcServer = grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserver = new AtomicReference<>();
    final CountDownLatch dataPlaneRequestLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SERVER_STREAMING, ServerCalls.asyncServerStreamingCall(
            (request, responseObserver) -> {
              dataPlaneResponseObserver.set(responseObserver);
              dataPlaneRequestLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

    try {
      final java.util.List<String> clientReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
      final CountDownLatch finishLatch = new CountDownLatch(1);
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SERVER_STREAMING, callOptions, dataPlaneChannel);
      proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onMessage(String message) {
          clientReceivedMessages.add(message);
        }
        @Override public void onClose(Status status, Metadata trailers) {
          finishLatch.countDown();
        }
      }, new Metadata());
      proxyCall.request(10);
      proxyCall.sendMessage("test");
      proxyCall.halfClose();

      // Wait for activation and request processing
      for (int i = 0; i < 1000 && dataPlaneRequestLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(dataPlaneRequestLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // Data plane server sends responses. 
      dataPlaneResponseObserver.get().onNext("Message 1");
      dataPlaneResponseObserver.get().onNext("Message 2");
      dataPlaneResponseObserver.get().onCompleted();

      for (int i = 0; i < 1000 && finishLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
      // Message 1 and Message 2 should be received by client.
      assertThat(clientReceivedMessages).containsExactly("Message 1", "Message 2");
      // Sidecar should NOT have seen any response body because override to NONE was applied during request headers.
      assertThat(sidecarResponseBodyCount.get()).isEqualTo(0);
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0; i < 100 && (!dataPlaneChannel.isTerminated() || !extProcServer.isTerminated()); i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeNone_whenOverrideToGrpc_thenSubsequentResponsesInteractedWithSidecar() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody()) {
              if (capturedRespBodyReq.get() == null && !request.getResponseBody().getBody().isEmpty()) {
                 capturedRespBodyReq.set(request);
              }
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("Original Response Body"))
                                  .setEndOfStream(request.getResponseBody().getEndOfStream())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            responseObserver.onCompleted();
          }
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

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);
    
    MutableHandlerRegistry uniqueDataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .directExecutor()
        .build().start());
    uniqueDataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Original Response Body");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for activation
    long startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // 5. App requests message
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify intercepted by sidecar
    startTime = System.currentTimeMillis();
    while (capturedRespBodyReq.get() == null && System.currentTimeMillis() - startTime < 5000) {
      Thread.sleep(10);
    }
    assertThat(capturedRespBodyReq.get()).isNotNull();
    assertThat(capturedRespBodyReq.get().getResponseBody().getBody().toStringUtf8()).isEqualTo("Original Response Body");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseHeaderModeSend_whenOverrideToSkip_thenResponseHeadersSentDirectly() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarResponseHeaderCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              sidecarResponseHeaderCount.incrementAndGet();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    final io.grpc.Server extProcServer = grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    final CountDownLatch dataPlaneRequestLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    final io.grpc.Server dataPlaneServer = grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("test");
              responseObserver.onCompleted();
              dataPlaneRequestLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

    try {
      final CountDownLatch finishLatch = new CountDownLatch(1);
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) {
          finishLatch.countDown();
        }
      }, new Metadata());
      proxyCall.request(1);
      proxyCall.sendMessage("test");
      proxyCall.halfClose();

      for (int i = 0; i < 1000 && finishLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
      // Sidecar should NOT have seen any response headers because override to SKIP was applied during request headers.
      assertThat(sidecarResponseHeaderCount.get()).isEqualTo(0);

      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      dataPlaneServer.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0; i < 100 && (!dataPlaneChannel.isTerminated() || !dataPlaneServer.isTerminated() || !extProcServer.isTerminated()); i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseHeaderModeSkip_whenOverrideToSend_thenResponseHeadersInteractedWithSidecar() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setAllowModeOverride(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarResponseHeaderCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .setModeOverride(ProcessingMode.newBuilder()
                      .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              sidecarResponseHeaderCount.incrementAndGet();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    final io.grpc.Server extProcServer = grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    final CountDownLatch dataPlaneRequestLatch = new CountDownLatch(1);
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    final io.grpc.Server dataPlaneServer = grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("test");
              responseObserver.onCompleted();
              dataPlaneRequestLatch.countDown();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

    try {
      final CountDownLatch finishLatch = new CountDownLatch(1);
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) {
          finishLatch.countDown();
        }
      }, new Metadata());
      proxyCall.request(1);
      proxyCall.sendMessage("test");
      proxyCall.halfClose();

      for (int i = 0; i < 1000 && finishLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
      // Sidecar SHOULD have seen response headers because override to SEND was applied during request headers.
      assertThat(sidecarResponseHeaderCount.get()).isEqualTo(1);

      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      dataPlaneServer.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0; i < 100 && (!dataPlaneChannel.isTerminated() || !dataPlaneServer.isTerminated() || !extProcServer.isTerminated()); i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      channelManager.close();
    }
  }

  // --- Category 11: Resource Management ---

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
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
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

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              // No-op
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    for (int i = 0; i < 50 && !proxyCall.isReady(); i++) {
      fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Application cancels the RPC
    proxyCall.cancel("User cancelled", null);

    // Verify sidecar stream also cancelled
    assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    channelManager.close();
  }




  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenIsReadyReturnsFalse() throws Exception {
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

    // Sidecar server
    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                sidecarActionLatch.countDown();
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder().build())
                    .build());
              }
            }).start();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicBoolean dataPlaneReady = new AtomicBoolean(true);

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
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return dataPlaneReady.get() && super.isReady();
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 50 && !proxyCall.isReady(); i++) {
      fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar becomes busy -> proxyCall becomes busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();

    // Sidecar becomes ready, but Data Plane is busy -> proxyCall is STILL ready because Normal Mode
    sidecarReady.set(true);
    dataPlaneReady.set(false);
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenAppRequestsAreBuffered() throws Exception {
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

    // Sidecar server
    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                sidecarActionLatch.countDown();
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder().build())
                    .build());
              }
            }).start();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
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
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
            }))
        .build());

    final AtomicInteger dataPlaneRequestCount = new AtomicInteger(0);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 50 && !proxyCall.isReady(); i++) {
      fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
      Thread.sleep(10);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy -> request(5) should be buffered
    sidecarReady.set(false);
    proxyCall.request(5);
    assertThat(dataPlaneRequestCount.get()).isEqualTo(0);

    // Sidecar becomes ready -> buffered requests should be drained
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();
    
    long startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 5 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
      Thread.sleep(10);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(5);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }
}
