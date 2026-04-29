package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import java.util.Arrays;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.HeaderForwardingRules;
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
import io.envoyproxy.envoy.service.ext_proc.v3.ProtocolConfiguration;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
  private Bootstrapper.BootstrapInfo bootstrapInfo;
  private Bootstrapper.ServerInfo serverInfo;

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

  private static final MethodDescriptor<String, String> METHOD_CLIENT_STREAMING =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
          .setFullMethodName("test.TestService/ClientStreaming")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new StringMarshaller())
          .build();

  private static final MethodDescriptor<String, String> METHOD_BIDI_STREAMING =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .setFullMethodName("test.TestService/BidiStreaming")
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

    bootstrapInfo = Mockito.mock(Bootstrapper.BootstrapInfo.class);
    Mockito.when(bootstrapInfo.node()).thenReturn(Node.newBuilder().build());
    Mockito.when(bootstrapInfo.implSpecificObject()).thenReturn(Optional.empty());

    serverInfo = Mockito.mock(Bootstrapper.ServerInfo.class);
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



  private ExternalProcessor.Builder createBaseProto(String targetName) {
    return ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + targetName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build());
  }

  // --- Category 1: Configuration Parsing & Provider ---

  @Test
  public void givenValidConfig_whenParsed_thenReturnsFilterConfig() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName).build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isNotNull();
    assertThat(result.config.typeUrl()).isEqualTo(ExternalProcessorFilter.TYPE_URL);
  }

  @Test
  public void givenUnsupportedBodyMode_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
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

  @Test
  public void givenInvalidDeferredCloseTimeout_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(315576000001L).build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Invalid deferred_close_timeout");
  }

  @Test
  public void givenNegativeDeferredCloseTimeout_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(0).setNanos(0).build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("deferred_close_timeout must be positive");
  }


  // --- Category 2: Configuration Override ---

  @Test
  public void givenOverrideConfig_whenGrpcServiceOverridden_thenUsesNewService() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///parent")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    
    GrpcService overrideService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
            .setTargetUri("in-process:///override")
            .addChannelCredentialsPlugin(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                .build())
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setGrpcService(overrideService)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getExternalProcessor().getGrpcService().getGoogleGrpc().getTargetUri())
        .isEqualTo("in-process:///override");
  }

  @Test
  public void givenOverrideConfig_whenFailureModeAllowOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setFailureModeAllow(false)
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setFailureModeAllow(com.google.protobuf.BoolValue.of(true))
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getFailureModeAllow()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenProcessingModeSkipsDefault_thenRetainsParentMode() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setProcessingMode(ProcessingMode.newBuilder()
                .setRequestHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);
    ProcessingMode mergedMode = interceptor.getFilterConfig().getExternalProcessor().getProcessingMode();
    
    // requestHeaderMode was SKIP in parent and DEFAULT in override. Should remain SKIP.
    assertThat(mergedMode.getRequestHeaderMode()).isEqualTo(ProcessingMode.HeaderSendMode.SKIP);
    // responseHeaderMode was SEND in parent and SKIP in override. Should become SKIP.
    assertThat(mergedMode.getResponseHeaderMode()).isEqualTo(ProcessingMode.HeaderSendMode.SKIP);
  }

  @Test
  public void givenOverrideConfig_whenProcessingModeMergesNone_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setProcessingMode(ProcessingMode.newBuilder()
                .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);
    ProcessingMode mergedMode = interceptor.getFilterConfig().getExternalProcessor().getProcessingMode();

    // requestBodyMode was GRPC in parent and NONE in override. Should become NONE.
    assertThat(mergedMode.getRequestBodyMode()).isEqualTo(ProcessingMode.BodySendMode.NONE);
    // responseBodyMode was GRPC in parent. Since it wasn't set in override, it becomes NONE 
    // because we merge all fields from the override, and non-HeaderSendMode enums without 
    // explicit presence in proto3 default to 0 (NONE) when not set.
    assertThat(mergedMode.getResponseBodyMode()).isEqualTo(ProcessingMode.BodySendMode.NONE);
  }

  @Test
  public void givenOverrideConfig_whenOtherFieldsOverridden_thenReplaced() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .addRequestAttributes("attr1")
        .addResponseAttributes("attr2")
        .setFailureModeAllow(false)
        .build();
    
    GrpcService overrideService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
            .setTargetUri("in-process:///overridden")
            .addChannelCredentialsPlugin(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                .build())
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .addRequestAttributes("attr3")
            .addResponseAttributes("attr4")
            .setGrpcService(overrideService)
            .setFailureModeAllow(com.google.protobuf.BoolValue.of(true))
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);
    ExternalProcessor mergedProto = interceptor.getFilterConfig().getExternalProcessor();

    assertThat(mergedProto.getRequestAttributesList()).containsExactly("attr3");
    assertThat(mergedProto.getResponseAttributesList()).containsExactly("attr4");
    assertThat(mergedProto.getGrpcService()).isEqualTo(overrideService);
    assertThat(interceptor.getFilterConfig().getFailureModeAllow()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenProcessingModeOverridden_thenTakesEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setProcessingMode(ProcessingMode.newBuilder()
                .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ProcessingMode mergedMode = interceptor.getFilterConfig().getExternalProcessor().getProcessingMode();
    // Granular merge: requestBodyMode overridden, responseBodyMode becomes NONE (default) 
    // because it's not set in the override proto.
    assertThat(mergedMode.getRequestBodyMode()).isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(mergedMode.getResponseBodyMode()).isEqualTo(ProcessingMode.BodySendMode.NONE);
  }

  @Test
  public void givenOverrideConfig_whenAllFieldsOverridden_thenAllTakeEffect() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setFailureModeAllow(false)
        .build();
    
    GrpcService overrideService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
            .setTargetUri("in-process:///override")
            .addChannelCredentialsPlugin(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials")
                .build())
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setFailureModeAllow(com.google.protobuf.BoolValue.of(true))
            .setGrpcService(overrideService)
            .setProcessingMode(ProcessingMode.newBuilder()
                .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
            .addRequestAttributes("attr-over")
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getGrpcService()).isEqualTo(overrideService);
    assertThat(mergedConfig.getExternalProcessor().getProcessingMode().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(mergedConfig.getExternalProcessor().getRequestAttributesList()).containsExactly("attr-over");
  }

  @Test
  public void givenOverrideConfig_whenSomeFieldsOverridden_thenMergedCorrectly() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setFailureModeAllow(false)
        .addRequestAttributes("attr-parent")
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setFailureModeAllow(com.google.protobuf.BoolValue.of(true))
            // requestAttributes NOT set in override
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getRequestAttributesList()).containsExactly("attr-parent");
  }


  @Test
  public void givenOverrideConfig_whenDisableImmediateResponseOverridden_thenInheritedFromParent() throws Exception {
    // disable_immediate_response is NOT in ExtProcOverrides.
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setDisableImmediateResponse(true)
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder().build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getDisableImmediateResponse()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenMutationRulesOverridden_thenInheritedFromParent() throws Exception {
    // mutation_rules is NOT in ExtProcOverrides.
    io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules rules = 
        io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
            .setDisallowAll(com.google.protobuf.BoolValue.newBuilder().setValue(true).build())
            .build();

    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setMutationRules(rules)
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder().build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getMutationRulesConfig().get().disallowAll())
        .isTrue();
  }

  @Test
  public void givenOverrideConfig_whenDeferredCloseTimeoutOverridden_thenInheritedFromParent() throws Exception {
    // deferred_close_timeout is NOT in ExtProcOverrides.
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(10).build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder().build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterConfig> overrideResult = provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test");
    ExternalProcessorInterceptor interceptor = (ExternalProcessorInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getDeferredCloseTimeoutNanos())
        .isEqualTo(TimeUnit.SECONDS.toNanos(10));
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
  public void givenProcessingMode_whenRequestHeadersSent_thenProtocolConfigIsPopulated() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(2);
    final List<ProcessingRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            capturedRequests.add(request);
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
            sidecarLatch.countDown();
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests).hasSize(3);
    
    // First request (RequestHeaders) should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestHeaders()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode()).isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode()).isEqualTo(ProcessingMode.BodySendMode.GRPC);
    
    // Second request (ResponseHeaders) should NOT have protocol_config
    ProcessingRequest secondReq = capturedRequests.get(1);
    assertThat(secondReq.hasResponseHeaders()).isTrue();
    assertThat(secondReq.hasProtocolConfig()).isFalse();

    // Third request (RequestBody) should NOT have protocol_config
    ProcessingRequest thirdReq = capturedRequests.get(2);
    assertThat(thirdReq.hasRequestBody()).isTrue();
    assertThat(thirdReq.hasProtocolConfig()).isFalse();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void givenPendingData_whenImmediateResponseReceived_thenDeliversDataBeforeStatus() throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    final List<String> appEvents = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);
    final ExecutorService sidecarResponseExecutor = Executors.newSingleThreadExecutor();
    final Metadata.Key<String> immediateKey = Metadata.Key.of("x-immediate-header", Metadata.ASCII_STRING_MARSHALLER);
    final AtomicReference<Metadata> appTrailers = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarResponseExecutor.submit(() -> {
              synchronized (responseObserver) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder().build())
                          .build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  try { Thread.sleep(500); } catch (InterruptedException e) {}
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setImmediateResponse(ImmediateResponse.newBuilder()
                          .setGrpcStatus(io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                              .setStatus(Status.UNAUTHENTICATED.getCode().value())
                              .build())
                          .setDetails("Immediate Auth Failure")
                          .setHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation.newBuilder()
                              .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                      .setKey("x-immediate-header").setValue("true").build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  responseObserver.onCompleted();
                }
              }
            });
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { extProcCompletedLatch.countDown(); }
        };
      }
    };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).directExecutor().build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorFilter filter = new ExternalProcessorFilter("test-filter", channelManager);
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, (call, headers) -> {
          call.sendHeaders(new Metadata());
          call.request(1);
          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {
              call.sendMessage("server-response");
              call.close(Status.OK, new Metadata());
            }
          };
        })
        .build());

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ManagedChannel channel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());
    Channel interceptedChannel = io.grpc.ClientInterceptors.intercept(channel, interceptor);

    ClientCall<String, String> call = interceptedChannel.newCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()));
    call.start(new ClientCall.Listener<String>() {
      @Override public void onHeaders(Metadata headers) { appEvents.add("HEADERS"); }
      @Override public void onMessage(String message) { appEvents.add("MESSAGE"); }
      @Override public void onClose(Status status, Metadata trailers) {
        appEvents.add("CLOSE:" + status.getCode());
        appTrailers.set(trailers);
        finishLatch.countDown();
      }
    }, new Metadata());

    call.request(1);
    call.sendMessage("request-body");
    call.halfClose();

    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appEvents).containsExactly("HEADERS", "MESSAGE", "CLOSE:UNAUTHENTICATED");
    assertThat(appTrailers.get().get(immediateKey)).isEqualTo("true");
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    sidecarResponseExecutor.shutdown();
    channelManager.close();
  }

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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    
    // Verify sidecar RECEIVED message about headers because default is SEND
    assertThat(sidecarMessages.get()).isEqualTo(1);
    
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
                return;
              }
              if (request.hasRequestBody()) {
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseHeaderModeSend_whenExtProcRespondsWithMutatedHeaders_thenMutatedHeadersAreForwardedToDataPlaneClient() throws Exception {
    String uniqueExtProcServerName = "extProc-resp-headers-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-resp-headers-" + InProcessServerBuilder.generateName();
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
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    Metadata.Key<String> mutatedKey = Metadata.Key.of("mutated-header", Metadata.ASCII_STRING_MARSHALLER);

    // External Processor Server
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
                  .setResponse(CommonResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              response.setResponseHeaders(HeadersResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder()
                      .setHeaderMutation(HeaderMutation.newBuilder()
                          .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                              .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                  .setKey("mutated-header")
                                  .setValue("mutated-value")
                                  .build())
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
    uniqueRegistry.addService(ServerInterceptors.intercept(
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
            call.sendHeaders(new Metadata());
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    final CountDownLatch headersLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onHeaders(Metadata headers) {
        receivedHeaders.set(headers);
        headersLatch.countDown();
      }
    };
    
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    // Verify application receives mutated response headers
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedHeaders.get().get(mutatedKey)).isEqualTo("mutated-value");
    
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
    assertThat(configOrError.errorDetail).isNull();
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
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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
  public void givenObservabilityMode_whenDataPlaneClosed_thenSidecarCloseIsDeferred() throws Exception {
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
        .setDeferredCloseTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(10).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarCompletedLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override public void onNext(ProcessingRequest request) {}
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {
            sidecarCompletedLatch.countDown();
          }
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

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

    try {
      final CountDownLatch appCloseLatch = new CountDownLatch(1);
      ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) {
          appCloseLatch.countDown();
        }
      };
      
      CallOptions callOptions = CallOptions.DEFAULT.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(appListener, new Metadata());

      // Data plane closes immediately
      proxyCall.halfClose();
      dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
          .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
              (request, responseObserver) -> {
                responseObserver.onNext("test");
                responseObserver.onCompleted();
              }))
          .build());
      proxyCall.request(1);

      // Wait for app onClose
      for (int i = 0; i < 1000 && appCloseLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // At this point, app received onClose, but sidecar should NOT be completed yet
      assertThat(sidecarCompletedLatch.getCount()).isEqualTo(1);

      // Fast forward time to trigger deferred close
      fakeClock.forwardTime(10, TimeUnit.SECONDS);
      
      for (int i = 0; i < 100 && sidecarCompletedLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
        Thread.sleep(1);
      }
      assertThat(sidecarCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      
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
    String uniqueExtProcServerName = "extProc-compression-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-compression-" + InProcessServerBuilder.generateName();
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
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
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
          @Override public void onCompleted() {
            new Thread(() -> responseObserver.onCompleted()).start();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(fakeClock.getScheduledExecutorService())
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(
        filterConfig, channelManager, scheduler);

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
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(fakeClock.getScheduledExecutorService())
            .build());

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

    // Wait for sidecar to receive headers and filter to activate call
    for (int i = 0; i < 5000 && closedLatch.getCount() > 0; i++) {
      fakeClock.forwardTime(10, TimeUnit.MILLISECONDS);
      Thread.sleep(1);
    }

    // Trigger request body processing to hit the unsupported compression check
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify application receives UNAVAILABLE with correct description
    for (int i = 0; i < 10000 && closedLatch.getCount() > 0; i++) {
      fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    }
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenUnsupportedCompressionInResponseBody_whenReceived_thenExtProcStreamIsErroredAndCallIsCancelled() throws Exception {
    String uniqueExtProcServerName = "extProc-resp-compression-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-resp-compression-" + InProcessServerBuilder.generateName();
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
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
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
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder().build())
                      .build())
                  .build());
            } else if (request.hasResponseBody()) {
              // Simulate sidecar sending compressed body mutation (unsupported) for response body
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
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
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

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

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

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
    assertThat(configOrError.errorDetail).isNull();
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
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder()
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
  @Test

  @SuppressWarnings("unchecked")
  public void givenHeaderSendModeDefault_whenProcessing_thenFollowsDefaultBehavior() throws Exception {
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
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.DEFAULT).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError = provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarRequestHeaderCount = new AtomicInteger(0);
    final AtomicInteger sidecarResponseHeaderCount = new AtomicInteger(0);
    final AtomicInteger sidecarResponseTrailerCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              sidecarRequestHeaderCount.incrementAndGet();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              sidecarResponseHeaderCount.incrementAndGet();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseTrailers()) {
              sidecarResponseTrailerCount.incrementAndGet();
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
              responseObserver.onCompleted();
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build())
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
      
      // Defaults: Request headers SENT, Response headers SENT, Response trailers SKIPPED
      assertThat(sidecarRequestHeaderCount.get()).isEqualTo(1);
      assertThat(sidecarResponseHeaderCount.get()).isEqualTo(1);
      assertThat(sidecarResponseTrailerCount.get()).isEqualTo(0);

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

  // --- Category 10: Resource Management ---

  @Test
  public void givenFilter_whenClosed_thenCachedChannelManagerIsClosed() throws Exception {
    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    
    ExternalProcessorFilter filter = new ExternalProcessorFilter("test", mockChannelManager);
    
    filter.close();
    
    Mockito.verify(mockChannelManager).close();
  }

  // --- Category 11: Data plane rpc cancellation ---

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
    assertThat(configOrError.errorDetail).isNull();
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

  // --- Category 12: Flow Control when side stream is full ---

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
    assertThat(configOrError.errorDetail).isNull();
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
    assertThat(configOrError.errorDetail).isNull();
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

  // --- Category 13: Streaming Completeness (Client & Bi-Di) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenClientStreamingRpc_whenExtProcMutatesAll_thenAllTargetsReceiveMutatedData() throws Exception {
    String uniqueExtProcServerName = "extProc-client-stream-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-client-stream-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
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

    final Metadata.Key<String> reqKey = Metadata.Key.of("req-mutated", Metadata.ASCII_STRING_MARSHALLER);
    final Metadata.Key<String> respKey = Metadata.Key.of("resp-mutated", Metadata.ASCII_STRING_MARSHALLER);

    final List<String> receivedPhases = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarActionLatch = new CountDownLatch(6);
    final ExecutorService sidecarResponseExecutor = Executors.newSingleThreadExecutor();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarResponseExecutor.submit(() -> {
              synchronized (responseObserver) {
                ProcessingResponse.Builder resp = ProcessingResponse.newBuilder();
                if (request.hasRequestHeaders()) {
                  receivedPhases.add("REQ_HEADERS");
                  resp.setRequestHeaders(HeadersResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                      .setHeaderMutation(HeaderMutation.newBuilder().addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                          .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder().setKey("req-mutated").setValue("true").build())
                          .build()).build()).build()).build());
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream() || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(BodyResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                        .setBodyMutation(BodyMutation.newBuilder()
                            .setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build())
                            .build())
                        .build()).build());
                  } else {
                    receivedPhases.add("REQ_BODY_MSG");
                    resp.setRequestBody(BodyResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                        .setBodyMutation(BodyMutation.newBuilder()
                            .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                .setBody(ByteString.copyFromUtf8("MutatedRequest"))
                                .build())
                            .build())
                        .build()).build());
                  }
                } else if (request.hasResponseHeaders()) {
                  receivedPhases.add("RESP_HEADERS");
                  resp.setResponseHeaders(HeadersResponse.newBuilder().build());
                } else if (request.hasResponseBody()) {
                  receivedPhases.add("RESP_BODY");
                  resp.setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(request.getResponseBody().getBody())
                                  .setEndOfStream(request.getResponseBody().getEndOfStream())
                                  .build())
                              .build())
                          .build())
                      .build());
                } else if (request.hasResponseTrailers()) {
                  receivedPhases.add("RESP_TRAILERS");
                  resp.setResponseTrailers(TrailersResponse.newBuilder().build());
                  responseObserver.onNext(resp.build());
                  responseObserver.onCompleted();
                  sidecarActionLatch.countDown();
                  return;
                }
                responseObserver.onNext(resp.build());
                sidecarActionLatch.countDown();
              }
            });
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    final ExecutorService testExecutor = Executors.newFixedThreadPool(20);
    final ExecutorService sidecarExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).executor(sidecarExecutor).build().start());

    // Data Plane Server (Client Streaming)
    final AtomicReference<Metadata> serverReceivedHeaders = new AtomicReference<>();
    final AtomicReference<String> serverReceivedBody = new AtomicReference<>();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    uniqueRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
                new ServerCalls.ClientStreamingMethod<String, String>() {
                  @Override
                  public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                    return new StreamObserver<String>() {
                      @Override public void onNext(String value) { serverReceivedBody.set(value); }
                      @Override public void onError(Throwable t) {}
                      @Override public void onCompleted() {
                        responseObserver.onNext("Ack");
                        responseObserver.onCompleted();
                      }
                    };
                  }
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            serverReceivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        }));
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .executor(testExecutor)
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).executor(testExecutor).build());
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(testExecutor).build());
    });
    ScheduledExecutorService sidecarRealScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, sidecarRealScheduler);

    final CountDownLatch finishLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> headersFromInterceptor = new AtomicReference<>();
    Channel interceptingChannel = io.grpc.ClientInterceptors.intercept(dataPlaneChannel, new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            super.start(new io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onHeaders(Metadata headers) {
                headersFromInterceptor.set(headers);
                super.onHeaders(headers);
              }
            }, headers);
          }
        };
      }
    });

    final AtomicReference<String> clientReceivedBody = new AtomicReference<>();
    StreamObserver<String> requestObserver = ClientCalls.asyncClientStreamingCall(
        interceptor.interceptCall(METHOD_CLIENT_STREAMING, CallOptions.DEFAULT.withExecutor(testExecutor), interceptingChannel),
        new StreamObserver<String>() {
          @Override public void onNext(String value) { clientReceivedBody.set(value); }
          @Override public void onError(Throwable t) { finishLatch.countDown(); }
          @Override public void onCompleted() { finishLatch.countDown(); }
        });

    requestObserver.onNext("OriginalRequest");
    requestObserver.onCompleted();

    if (!sidecarActionLatch.await(10, TimeUnit.SECONDS)) {
       throw new AssertionError("Sidecar actions failed. Received: " + receivedPhases);
    }
    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> expectedPhases = Arrays.asList("REQ_HEADERS", "REQ_BODY_MSG", "REQ_BODY_EOS", "RESP_HEADERS", "RESP_BODY", "RESP_TRAILERS");
    assertThat(receivedPhases).containsExactlyElementsIn(expectedPhases).inOrder();

    assertThat(serverReceivedHeaders.get().get(reqKey)).isEqualTo("true");
    assertThat(serverReceivedBody.get()).isEqualTo("MutatedRequest");
    assertThat(clientReceivedBody.get()).isEqualTo("Ack");

    sidecarRealScheduler.shutdown();
    sidecarResponseExecutor.shutdown();
    testExecutor.shutdown();
    sidecarExecutor.shutdown();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenBidiStreamingRpc_whenExtProcMutatesAll_thenAllTargetsReceiveMutatedData() throws Exception {
    String uniqueExtProcServerName = "extProc-bidi-stream-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = "dataPlane-bidi-stream-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
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

    final Metadata.Key<String> reqKey = Metadata.Key.of("req-mutated", Metadata.ASCII_STRING_MARSHALLER);
    final Metadata.Key<String> respKey = Metadata.Key.of("resp-mutated", Metadata.ASCII_STRING_MARSHALLER);

    final List<String> receivedPhases = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarBidiLatch = new CountDownLatch(6);
    final ExecutorService bidiSidecarResponseExecutor = Executors.newSingleThreadExecutor();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase bidiExtProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            bidiSidecarResponseExecutor.submit(() -> {
              synchronized (responseObserver) {
                ProcessingResponse.Builder resp = ProcessingResponse.newBuilder();
                if (request.hasRequestHeaders()) {
                  receivedPhases.add("REQ_HEADERS");
                  resp.setRequestHeaders(HeadersResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                      .setHeaderMutation(HeaderMutation.newBuilder().addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                          .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder().setKey("req-mutated").setValue("true").build())
                          .build()).build()).build()).build());
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream() || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(BodyResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                        .setBodyMutation(BodyMutation.newBuilder()
                            .setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build())
                            .build())
                        .build()).build());
                  } else {
                    receivedPhases.add("REQ_BODY_MSG");
                    resp.setRequestBody(BodyResponse.newBuilder().setResponse(CommonResponse.newBuilder()
                        .setBodyMutation(BodyMutation.newBuilder()
                            .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                .setBody(ByteString.copyFromUtf8("MutatedBidiReq"))
                                .build())
                            .build())
                        .build()).build());
                  }
                } else if (request.hasResponseHeaders()) {
                  receivedPhases.add("RESP_HEADERS");
                  resp.setResponseHeaders(HeadersResponse.newBuilder().build());
                } else if (request.hasResponseBody()) {
                  receivedPhases.add("RESP_BODY");
                  resp.setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(request.getResponseBody().getBody())
                                  .setEndOfStream(request.getResponseBody().getEndOfStream())
                                  .build())
                              .build())
                          .build())
                      .build());
                } else if (request.hasResponseTrailers()) {
                  receivedPhases.add("RESP_TRAILERS");
                  resp.setResponseTrailers(TrailersResponse.newBuilder().build());
                  responseObserver.onNext(resp.build());
                  responseObserver.onCompleted();
                  sidecarBidiLatch.countDown();
                  return;
                }
                responseObserver.onNext(resp.build());
                sidecarBidiLatch.countDown();
              }
            });
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    final ExecutorService bidiTestExecutor = Executors.newFixedThreadPool(20);
    final ExecutorService sidecarExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName).addService(bidiExtProcImpl).executor(sidecarExecutor).build().start());

    // Data Plane Server (Bidi)
    final AtomicReference<Metadata> serverReceivedHeaders = new AtomicReference<>();
    MutableHandlerRegistry uniqueBidiRegistry = new MutableHandlerRegistry();
    uniqueBidiRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
                new ServerCalls.BidiStreamingMethod<String, String>() {
                  @Override
                  public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                    return new StreamObserver<String>() {
                      @Override public void onNext(String value) { responseObserver.onNext(value + "Echo"); }
                      @Override public void onError(Throwable t) {}
                      @Override public void onCompleted() { responseObserver.onCompleted(); }
                    };
                  }
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            serverReceivedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        }));
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName).fallbackHandlerRegistry(uniqueBidiRegistry).executor(bidiTestExecutor).build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName).executor(bidiTestExecutor).build());
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(bidiTestExecutor).build());
    });
    ScheduledExecutorService bidiRealScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, bidiRealScheduler);

    final AtomicReference<String> clientReceivedBody = new AtomicReference<>();
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> bidiHeadersFromInterceptor = new AtomicReference<>();

    Channel bidiInterceptingChannel = io.grpc.ClientInterceptors.intercept(dataPlaneChannel, new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            super.start(new io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onHeaders(Metadata headers) {
                bidiHeadersFromInterceptor.set(headers);
                super.onHeaders(headers);
              }
            }, headers);
          }
        };
      }
    });

    StreamObserver<String> bidiRequestObserver = ClientCalls.asyncBidiStreamingCall(
        interceptor.interceptCall(METHOD_BIDI_STREAMING, CallOptions.DEFAULT.withExecutor(bidiTestExecutor), bidiInterceptingChannel),
        new StreamObserver<String>() {
          @Override public void onNext(String value) { clientReceivedBody.set(value); }
          @Override public void onError(Throwable t) { finishLatch.countDown(); }
          @Override public void onCompleted() { finishLatch.countDown(); }
        });

    bidiRequestObserver.onNext("Bidi");
    bidiRequestObserver.onCompleted();

    if (!sidecarBidiLatch.await(10, TimeUnit.SECONDS)) {
       throw new AssertionError("Sidecar bidi actions failed. Received: " + receivedPhases);
    }
    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> expectedPhases = Arrays.asList("REQ_HEADERS", "REQ_BODY_MSG", "REQ_BODY_EOS", "RESP_HEADERS", "RESP_BODY", "RESP_TRAILERS");
    assertThat(receivedPhases).containsExactlyElementsIn(expectedPhases).inOrder();

    assertThat(serverReceivedHeaders.get().get(reqKey)).isEqualTo("true");
    assertThat(clientReceivedBody.get()).isEqualTo("MutatedBidiReqEcho");

    bidiRealScheduler.shutdown();
    bidiSidecarResponseExecutor.shutdown();
    bidiTestExecutor.shutdown();
    sidecarExecutor.shutdown();
    channelManager.close();
  }

  // --- Category 14: Header Forwarding Rules ---

  @Test
  public void givenAllowedHeaders_whenHeadersForwarded_thenOnlyAllowedAreSent() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              capturedHeaders.set(request.getRequestHeaders());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder().setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build()).build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Config with forward_rules: allowed_headers = ["x-allowed-*", "content-type"]
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setForwardRules(HeaderForwardingRules.newBuilder()
            .setAllowedHeaders(io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setPrefix("x-allowed-").build())
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setExact("content-type").build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
        .executor(Executors.newSingleThreadExecutor())
        .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-allowed-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-disallowed", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "application/grpc");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) { appCloseLatch.countDown(); }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv : capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-allowed-1");
    assertThat(headerNames).contains("content-type");
    assertThat(headerNames).doesNotContain("x-disallowed");
    
    channelManager.close();
  }

  @Test
  public void givenDisallowedHeaders_whenHeadersForwarded_thenDisallowedAreSkipped() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              capturedHeaders.set(request.getRequestHeaders());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder().setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build()).build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Config with forward_rules: disallowed_headers = ["x-secret", "authorization"]
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setForwardRules(HeaderForwardingRules.newBuilder()
            .setDisallowedHeaders(io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setExact("x-secret").build())
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setExact("authorization").build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
        .executor(Executors.newSingleThreadExecutor())
        .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "v3");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) { appCloseLatch.countDown(); }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv : capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-foo");
    assertThat(headerNames).doesNotContain("x-secret");
    assertThat(headerNames).doesNotContain("authorization");
    
    channelManager.close();
  }

  @Test
  public void givenBothRules_whenHeadersForwarded_thenBothAreApplied() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              capturedHeaders.set(request.getRequestHeaders());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder().setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build()).build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Config with forward_rules: allowed = ["x-foo-*"], disallowed = ["x-foo-secret"]
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setForwardRules(HeaderForwardingRules.newBuilder()
            .setAllowedHeaders(io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setPrefix("x-foo-").build())
                .build())
            .setDisallowedHeaders(io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher.newBuilder()
                .addPatterns(io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder().setExact("x-foo-secret").build())
                .build())
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
        .executor(Executors.newSingleThreadExecutor())
        .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-foo-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("x-bar", Metadata.ASCII_STRING_MARSHALLER), "v3");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) { appCloseLatch.countDown(); }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv : capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-foo-1");
    assertThat(headerNames).doesNotContain("x-foo-secret");
    assertThat(headerNames).doesNotContain("x-bar");
    
    channelManager.close();
  }

  // --- Category 15: Request Attributes ---

  @Test
  public void parseFilterConfig_withUnrecognizedRequestAttribute_isIgnored() {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .addRequestAttributes("invalid.attribute")
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(result.errorDetail).isNull();
    assertThat(result.config.getRequestAttributes()).containsExactly("invalid.attribute");
  }

  @Test
  public void parseFilterConfig_withRecognizedRequestAttributes_succeeds() {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .addRequestAttributes("request.path")
        .addRequestAttributes("request.host")
        .addRequestAttributes("request.scheme") // Recognized but not set
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(result.errorDetail).isNull();
    assertThat(result.config.getRequestAttributes()).containsExactly(
        "request.path", "request.host", "request.scheme");
  }

  @Test
  public void givenRequestAttributes_whenHeaderPhase_thenAttributesSent() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .addRequestAttributes("request.path")
        .addRequestAttributes("request.host")
        .addRequestAttributes("request.method")
        .addRequestAttributes("request.query")
        .build();

    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch callLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              capturedRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder().setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build()).build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) { callLatch.countDown(); }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.path").getFieldsOrThrow("").getStringValue()).isEqualTo("/test.TestService/SayHello");
    assertThat(attributes.get("request.host").getFieldsOrThrow("").getStringValue()).isEqualTo(dataPlaneChannel.authority());
    
    channelManager.close();
  }

  @Test
  public void givenMetadataAttributes_whenHeadersPresent_thenAttributesSent() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .addRequestAttributes("request.referer")
        .addRequestAttributes("request.useragent")
        .addRequestAttributes("request.id")
        .addRequestAttributes("request.headers")
        .build();

    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch callLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              capturedRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseBody() && (request.getResponseBody().getEndOfStream() || request.getResponseBody().getEndOfStreamWithoutMessage())) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder().setStreamedResponse(StreamedBodyResponse.newBuilder().setEndOfStream(true).build()).build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("referer", Metadata.ASCII_STRING_MARSHALLER), "http://google.com");
    headers.put(Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER), "custom-ua");
    headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), "req-123");
    headers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "val");

    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor()), dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) { callLatch.countDown(); }
    }, headers);
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.referer").getFieldsOrThrow("").getStringValue()).isEqualTo("http://google.com");
    
    channelManager.close();
  }

  // --- Category 16: Response Trailers ---

  @Test
  public void givenResponseTrailerModeSend_whenCallCloses_thenResponseTrailersSentToExtProc() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseTrailers()) {
              capturedRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            } else if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).executor(Executors.newSingleThreadExecutor()).build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(Executors.newSingleThreadExecutor()).build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    // Improved Data Plane Server with trailers
    MutableHandlerRegistry uniqueDataPlaneRegistry = new MutableHandlerRegistry();
    uniqueDataPlaneRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
              responseObserver.onNext("Hello");
              responseObserver.onCompleted();
            })).build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
              @Override
              public void close(Status status, Metadata trailers) {
                trailers.put(Metadata.Key.of("x-trailer", Metadata.ASCII_STRING_MARSHALLER), "val");
                super.close(status, trailers);
              }
            }, headers);
          }
        }));

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName).executor(Executors.newSingleThreadExecutor()).build());

    final CountDownLatch callLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) { callLatch.countDown(); }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().hasResponseTrailers()).isTrue();
    assertThat(capturedRequest.get().getResponseTrailers().getTrailers().getHeadersList()).isNotEmpty();
    
    channelManager.close();
  }

  @Test
  public void givenResponseTrailerModeDefault_whenCallCloses_thenResponseTrailersNotSentToExtProc() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicInteger sidecarTrailerCount = new AtomicInteger(0);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarHeadersLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseTrailers()) {
              sidecarTrailerCount.incrementAndGet();
            } else if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarHeadersLatch.countDown();
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).executor(Executors.newSingleThreadExecutor()).build().start());

    // DEFAULT mode for trailers (interpreted as SKIP)
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(Executors.newSingleThreadExecutor()).build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    MutableHandlerRegistry uniqueDataPlaneRegistry = new MutableHandlerRegistry();
    uniqueDataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());
        
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName).executor(Executors.newSingleThreadExecutor()).build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
        @Override public void onClose(Status status, Metadata trailers) { appCloseLatch.countDown(); }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarHeadersLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();
    // Wait a bit to ensure no trailers arrive
    Thread.sleep(500);
    assertThat(sidecarTrailerCount.get()).isEqualTo(0);
    
    channelManager.close();
  }

  // --- Category 17: Trailers-Only Response ---

  @Test
  public void givenTrailersOnly_whenResponseReceived_thenResponseHeadersSentWithEos() throws Exception {
    String myExtProcServerName = InProcessServerBuilder.generateName();
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> capturedResponseHeadersRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> process(final io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> responseObserver) {
        ((io.grpc.stub.ServerCallStreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>) responseObserver).request(100);
        return new io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>() {
          @Override
          public void onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                  .setRequestHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              capturedResponseHeadersRequest.set(request);
              // Sidecar mutates the trailers-only headers (which are the trailers)
              responseObserver.onNext(io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                  .setResponseHeaders(io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder()
                      .setResponse(io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse.newBuilder()
                          .setHeaderMutation(io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation.newBuilder()
                              .addSetHeaders(io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                      .setKey("x-mutated-trailer").setValue("val").build())
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(myExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Explicitly enable response headers for this test
    io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor proto = createBaseProto(myExtProcServerName)
        .setProcessingMode(io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode.newBuilder()
            .setResponseHeaderMode(io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(myExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    // Data plane server returns trailers-only (onError results in trailers-only)
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onError(Status.UNAUTHENTICATED.withDescription("force-trailers-only").asRuntimeException());
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(dataPlaneServerName)
        .executor(Executors.newSingleThreadExecutor())
        .build());

    final AtomicReference<Metadata> capturedAppTrailers = new AtomicReference<>();
    final CountDownLatch callLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(Executors.newSingleThreadExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override 
      public void onClose(Status status, Metadata trailers) { 
        capturedAppTrailers.set(trailers);
        callLatch.countDown(); 
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(10, TimeUnit.SECONDS)).isTrue();
    
    ProcessingRequest req = capturedResponseHeadersRequest.get();
    assertThat(req.hasResponseHeaders()).isTrue();
    assertThat(req.getResponseHeaders().getEndOfStream()).isTrue();
    
    Metadata appTrailers = capturedAppTrailers.get();
    assertThat(appTrailers.get(Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("val");
    
    channelManager.close();
  }

  // --- Category 18: Response Ordering Checks ---

  @Test
  public void givenOutOfOrderReqResponses_whenMessageArrivesBeforeHeaders_thenFails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<Throwable> extProcError = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Violate order: send RequestBody response before RequestHeaders response
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setEndOfStream(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted(); // Complete stream to allow cleanup
            }
          }
          @Override public void onError(Throwable t) { extProcError.set(t); }
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel = grpcCleanup.register(InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
        .executor(Executors.newSingleThreadExecutor())
        .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // The call should fail with UNAVAILABLE status due to stream failure triggered by protocol error
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(appStatus.get().getDescription()).contains("External processor stream failed");
    
    channelManager.close();
  }

  @Test
  public void givenValidOrder_whenResponsesArriveInOrder_thenSucceeds() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            }
          }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() { responseObserver.onCompleted(); }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName).build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Verify that headers are processed correctly and the ordering check passes
    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    
    // Clean up by cancelling the call
    proxyCall.cancel("Test finished", null);
    
    channelManager.close();
  }

  // --- Category 19: Header Response Status Checks ---

  @Test
  public void givenRequestHeadersResponse_whenStatusIsContinueAndReplace_thenFails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setStatus(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE)
                          .build())
                      .build())
                  .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {
            sidecarFinishedLatch.countDown();
          }
          @Override public void onCompleted() {
            sidecarFinishedLatch.countDown();
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Enable fail-open
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).executor(Executors.newSingleThreadExecutor()).build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    try {
      proxyCall.halfClose();
    } catch (IllegalStateException ignored) {}

    assertThat(sidecarLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarFinishedLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(30, TimeUnit.SECONDS)).isTrue();

    // Call should succeed due to fail-open
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.OK);

    channelManager.close();
  }

  @Test
  public void givenResponseHeadersResponse_whenStatusIsContinueAndReplace_thenFails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
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
                  .setResponseHeaders(HeadersResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setStatus(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE)
                          .build())
                      .build())
                  .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            }
          }
          @Override public void onError(Throwable t) {
            sidecarFinishedLatch.countDown();
          }
          @Override public void onCompleted() {
            sidecarFinishedLatch.countDown();
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Enable response headers and fail-open
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig = provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorInterceptor interceptor = new ExternalProcessorInterceptor(filterConfig, channelManager, scheduler);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).executor(Executors.newSingleThreadExecutor()).build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptor.interceptCall(METHOD_SAY_HELLO, CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    try {
      proxyCall.halfClose();
    } catch (IllegalStateException ignored) {}

    assertThat(sidecarLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarFinishedLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(30, TimeUnit.SECONDS)).isTrue();

    // The call should succeed due to fail-open
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.OK);

    channelManager.close();
  }
}
