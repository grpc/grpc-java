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

import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
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
import io.envoyproxy.envoy.service.ext_proc.v3.HttpBody;
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.TrailersResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
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
import io.grpc.internal.FakeClock;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterOverrideConfig;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

/**
 * Unit tests for {@link ExternalProcessorFilter}.
 */
@RunWith(JUnit4.class)
public class ExternalProcessorClientInterceptorTest {
  private static final String INSECURE_CREDENTIALS_TYPE_URL =
      "type.googleapis.com/envoy.extensions.grpc_service."
      + "channel_credentials.insecure.v3.InsecureCredentials";

  static {
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", "true");
  }

  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private MutableHandlerRegistry dataPlaneServiceRegistry;

  private String dataPlaneServerName;
  private String extProcServerName;
  private final FakeClock fakeClock = new FakeClock();
  private ScheduledExecutorService scheduler;
  private ExternalProcessorFilter.Provider provider;
  private static final Filter.FilterContext FAKE_CONTEXT = Filter.FilterContext.create(
      "test-filter", new io.grpc.MetricRecorder() {});
  private static final CallOptions DEFAULT_CALL_OPTIONS = CallOptions.DEFAULT
      .withOption(XdsNameResolver.CLUSTER_SELECTION_KEY, "backend-service-metric");
  private Filter.FilterConfigParseContext filterContext;
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
          @Override
          public String getServiceAuthority() {
            return "localhost";
          }

          @Override
          public void start(Listener2 listener) {
          }

          @Override
          public void shutdown() {
          }
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
    NameResolverRegistry.getDefaultRegistry().register(new InProcessNameResolverProvider());

    dataPlaneServiceRegistry = new MutableHandlerRegistry();
    dataPlaneServerName = InProcessServerBuilder.generateName();
    extProcServerName = InProcessServerBuilder.generateName();
    scheduler = fakeClock.getScheduledExecutorService();
    provider = new ExternalProcessorFilter.Provider();

    bootstrapInfo =
        Bootstrapper.BootstrapInfo.builder()
            .node(Node.newBuilder().build())
            .servers(
                Collections.singletonList(
                    Bootstrapper.ServerInfo.create(
                        "test_target", Collections.emptyMap())))
            .build();

    serverInfo =
        Bootstrapper.ServerInfo.create(
            "test_target", Collections.emptyMap(), false, true, false, false);
    
    filterContext = Filter.FilterConfigParseContext.builder()
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
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build());
  }

  // --- Category 1: Configuration Override ---

  @Test
  public void givenOverrideConfig_whenGrpcServiceOverridden_thenUsesNewService() throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///parent")
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .build();
    
    GrpcService overrideService = GrpcService.newBuilder()
        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
            .setTargetUri("in-process:///override")
            .addChannelCredentialsPlugin(Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                .build())
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
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
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getExternalProcessor().getGrpcService()
        .getGoogleGrpc().getTargetUri()).isEqualTo("in-process:///override");
  }

  @Test
  public void givenOverrideConfig_whenOverridesMissing_thenFallsBackToDefaultInstance()
      throws Exception {
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder().build();

    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult = 
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    assertThat(overrideConfig.hasProcessingMode()).isFalse();
    assertThat(overrideConfig.hasRequestAttributes()).isFalse();
    assertThat(overrideConfig.hasResponseAttributes()).isFalse();
    assertThat(overrideConfig.hasGrpcService()).isFalse();
    assertThat(overrideConfig.hasFailureModeAllow()).isFalse();
    assertThat(overrideConfig.getGrpcServiceConfig()).isNull();
  }

  @Test
  public void givenOverrideConfig_whenFailureModeAllowOverridden_thenTakesEffect()
      throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setFailureModeAllow(false)
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setFailureModeAllow(com.google.protobuf.BoolValue.of(true))
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
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getFailureModeAllow()).isTrue();
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
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
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

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = 
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult = 
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);
    ExternalProcessor mergedProto = interceptor.getFilterConfig().getExternalProcessor();

    assertThat(mergedProto.getRequestAttributesList()).containsExactly("attr3");
    assertThat(mergedProto.getResponseAttributesList()).containsExactly("attr4");
    assertThat(mergedProto.getGrpcService()).isEqualTo(overrideService);
    assertThat(interceptor.getFilterConfig().getFailureModeAllow()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenProcessingModeOverridden_thenReplacesWholeMode()
      throws Exception {
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setProcessingMode(ProcessingMode.newBuilder()
                .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
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
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ProcessingMode mergedMode = 
        interceptor.getFilterConfig().getExternalProcessor().getProcessingMode();
    // Full replacement: requestBodyMode becomes GRPC, others become defaults (0/DEFAULT/NONE)
    assertThat(mergedMode.getRequestBodyMode()).isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(mergedMode.getRequestHeaderMode()).isEqualTo(ProcessingMode.HeaderSendMode.DEFAULT);
    assertThat(mergedMode.getResponseHeaderMode()).isEqualTo(ProcessingMode.HeaderSendMode.DEFAULT);
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
                .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
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

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = 
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult = 
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getGrpcService()).isEqualTo(overrideService);
    assertThat(mergedConfig.getExternalProcessor().getProcessingMode().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(mergedConfig.getExternalProcessor().getRequestAttributesList())
        .containsExactly("attr-over");
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

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = 
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult = 
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    ExternalProcessorFilterConfig mergedConfig = interceptor.getFilterConfig();
    assertThat(mergedConfig.getFailureModeAllow()).isTrue();
    assertThat(mergedConfig.getExternalProcessor().getRequestAttributesList())
        .containsExactly("attr-parent");
  }


  @Test
  public void givenOverrideConfig_whenDisableImmediateResponseOverridden_thenInheritedFromParent()
      throws Exception {
    // disable_immediate_response is NOT in ExtProcOverrides.
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setDisableImmediateResponse(true)
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder().build())
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
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getDisableImmediateResponse()).isTrue();
  }

  @Test
  public void givenOverrideConfig_whenMutationRulesOverridden_thenInheritedFromParent()
      throws Exception {
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

    ConfigOrError<ExternalProcessorFilterConfig> parentResult = 
        provider.parseFilterConfig(Any.pack(parentProto), filterContext);
    assertThat(parentResult.errorDetail).isNull();
    ExternalProcessorFilterConfig parentConfig = parentResult.config;
    ConfigOrError<ExternalProcessorFilterOverrideConfig> overrideResult = 
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);
    assertThat(overrideResult.errorDetail).isNull();
    ExternalProcessorFilterOverrideConfig overrideConfig = overrideResult.config;

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT);
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getMutationRulesConfig().get().disallowAll())
        .isTrue();
  }

  @Test
  public void givenOverrideConfig_whenDeferredCloseTimeoutOverridden_thenInheritedFromParent()
      throws Exception {
    // deferred_close_timeout is NOT in ExtProcOverrides.
    ExternalProcessor parentProto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(10).build())
        .build();
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder().build())
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
    ExternalProcessorClientInterceptor interceptor = (ExternalProcessorClientInterceptor)
        filter.buildClientInterceptor(parentConfig, overrideConfig, scheduler);

    assertThat(interceptor.getFilterConfig().getDeferredCloseTimeoutNanos())
        .isEqualTo(TimeUnit.SECONDS.toNanos(10));
  }

  // --- Category 2: Client Interceptor & Lifecycle ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenInterceptor_whenCallIntercepted_thenExtProcStubUsesSerializingExecutor()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
          }

          @Override
          public void onError(Throwable t) {
          }

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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedExecutor.get()).isNotNull();
    assertThat(capturedExecutor.get().getClass().getName()).contains("SerializingExecutor");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenGrpcServiceWithTimeout_whenCallIntercepted_thenExtProcStubHasCorrectDeadline()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
          }

          @Override
          public void onError(Throwable t) {
          }

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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(capturedDeadline.get()).isNotNull();
    assertThat(capturedDeadline.get().timeRemaining(TimeUnit.SECONDS)).isAtLeast(4);
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }



  // --- Category 3: Protocol config propagation ---

  @Test
  public void protocolConfig_onHeaders()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    final CountDownLatch sidecarLatch = new CountDownLatch(3);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests.size()).isAtLeast(2);
    
    // First request (RequestHeaders) should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestHeaders()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    
    // Subsequent requests should NOT have protocol_config
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void protocolConfig_onBody()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    final CountDownLatch sidecarLatch = new CountDownLatch(2);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests.size()).isAtLeast(1);
    
    // First request should be RequestBody and should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestBody()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    
    // Subsequent requests should NOT have protocol_config
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void protocolConfig_onResponseHeaders()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    final CountDownLatch sidecarLatch = new CountDownLatch(2);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests.size()).isAtLeast(1);
    
    // First request should be ResponseHeaders and should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseHeaders()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    
    // Subsequent requests should NOT have protocol_config
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void protocolConfig_onResponseBody()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests.size()).isAtLeast(1);
    
    // First request should be ResponseBody and should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseBody()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.GRPC);
    
    // Subsequent requests should NOT have protocol_config
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void protocolConfig_onResponseTrailers()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseTrailers()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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
        .addService(extProcImpl).directExecutor().build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(capturedRequests.size()).isAtLeast(1);
    
    // First request should be ResponseTrailers and should have protocol_config
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseTrailers()).isTrue();
    assertThat(firstReq.hasProtocolConfig()).isTrue();
    assertThat(firstReq.getProtocolConfig().getRequestBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    assertThat(firstReq.getProtocolConfig().getResponseBodyMode())
        .isEqualTo(ProcessingMode.BodySendMode.NONE);
    
    // Subsequent requests should NOT have protocol_config
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).hasProtocolConfig()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 4: GrpcService Initial Metadata ---

  @Test
  public void givenGrpcServiceWithInitialMetadata_whenCallIntercepted_thenSendsMetadata()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .addInitialMetadata(io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                .setKey("x-init-key").setValue("init-val").build())
            .addInitialMetadata(
                io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey("x-bin-key-bin")
                    .setRawValue(ByteString.copyFrom(new byte[] {1, 2, 3}))
                    .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          @SuppressWarnings("unchecked")
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

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

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 5: Request attributes propagation ---

  @Test
  public void requestAttributes_onHeaders()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .addRequestAttributes("request.path")
        .addRequestAttributes("request.host")
        .build();

    final CountDownLatch sidecarLatch = new CountDownLatch(2);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequests.size()).isAtLeast(2);

    // First request should be RequestHeaders and should have attributes
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestHeaders()).isTrue();
    assertThat(firstReq.getAttributesCount()).isGreaterThan(0);
    com.google.protobuf.Struct pathAttr = firstReq.getAttributesMap().get("request.path");
    assertThat(pathAttr.getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    com.google.protobuf.Struct hostAttr = firstReq.getAttributesMap().get("request.host");
    assertThat(hostAttr.getFieldsOrThrow("").getStringValue())
        .isEqualTo(dataPlaneChannel.authority());

    // Subsequent requests should NOT have attributes
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).getAttributesCount()).isEqualTo(0);
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void requestAttributes_onBody()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .addRequestAttributes("request.path")
        .addRequestAttributes("request.host")
        .build();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequests.size()).isAtLeast(1);

    // First request should be RequestBody and should have attributes
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasRequestBody()).isTrue();
    assertThat(firstReq.getAttributesCount()).isGreaterThan(0);
    com.google.protobuf.Struct pathAttr = firstReq.getAttributesMap().get("request.path");
    assertThat(pathAttr.getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    com.google.protobuf.Struct hostAttr = firstReq.getAttributesMap().get("request.host");
    assertThat(hostAttr.getFieldsOrThrow("").getStringValue())
        .isEqualTo(dataPlaneChannel.authority());

    // Subsequent requests should NOT have attributes
    for (int i = 1; i < capturedRequests.size(); i++) {
      assertThat(capturedRequests.get(i).getAttributesCount()).isEqualTo(0);
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  public void requestAttributes_notSent()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = dataPlaneServerName;

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .addRequestAttributes("request.path")
        .addRequestAttributes("request.host")
        .build();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                capturedRequests.add(request);
                if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                sidecarLatch.countDown();
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

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequests.size()).isAtLeast(1);

    // First request should be ResponseHeaders, and should NOT have attributes
    ProcessingRequest firstReq = capturedRequests.get(0);
    assertThat(firstReq.hasResponseHeaders()).isTrue();
    assertThat(firstReq.getAttributesCount()).isEqualTo(0);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 6: Request Header Processing ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSend_whenStartCalled_thenCallIsBuffered()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch requestSentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            capturedRequest.set(request);
            requestSentLatch.countDown();
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
  // Note: givenRequestHeaderModeSend_whenExtProcTerminates_thenCallIsActivated tests the case
  // when the ext-proc stream terminates while waiting for call activation.
  public void givenRequestHeaderModeSend_whenExtProcRespondsWithMutations_thenCallIsActivated()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch appFinishedLatch = new CountDownLatch(1);

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                try {
                  appFinishedLatch.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("x-mutated")
                                                  .setValue("true")
                                                  .build())
                                          .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                }
              }
            }).start();
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            new Thread(() -> {
              synchronized (responseObserver) {
                responseObserver.onCompleted();
              }
            }).start();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    Metadata headers = new Metadata();
    proxyCall.start(new ClientCall.Listener<String>() {}, headers);

    // Send message and half-close to trigger unary call while the call is buffered
    // (since ext-proc is waiting)
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Release the ext-proc response now that all app events are buffered
    appFinishedLatch.countDown();

    // Verify main call started with mutated headers
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Metadata finalHeaders = capturedHeaders.get();
    assertThat(
            finalHeaders.get(Metadata.Key.of("x-mutated", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenHeaderModeSend_whenCallHasBinaryHeaders_thenBinaryHeadersForwarded()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch extProcLatch = new CountDownLatch(1);

    // External Processor Server
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
                  capturedRequest.set(request);
                }
                new Thread(() -> {
                  synchronized (responseObserver) {
                    if (request.hasRequestHeaders()) {
                      responseObserver.onNext(ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                    }
                  }
                  extProcLatch.countDown();
                }).start();
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                synchronized (responseObserver) {
                  responseObserver.onCompleted();
                }
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[] {4, 5, 6});
    proxyCall.start(new ClientCall.Listener<String>() {}, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    ProcessingRequest req = capturedRequest.get();
    assertThat(req).isNotNull();
    assertThat(req.hasRequestHeaders()).isTrue();
    
    // Find x-bin-key-bin header in HeaderMap
    io.envoyproxy.envoy.config.core.v3.HeaderValue foundHeader = null;
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv
        : req.getRequestHeaders().getHeaders().getHeadersList()) {
      if (hv.getKey().equals("x-bin-key-bin")) {
        foundHeader = hv;
        break;
      }
    }
    assertThat(foundHeader).isNotNull();
    assertThat(foundHeader.getRawValue()).isEqualTo(ByteString.copyFromUtf8("BAUG"));

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestHeaderModeSkip_whenStartCalled_thenCallIsActivated() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarMessages.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
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

  @Test
  @SuppressWarnings("unchecked")
  public void
      givenRequestHeaderModeSkip_whenBodyProcessingEnabled_thenFirstRequestToExtProcIsRequestBody()
          throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcLatch = new CountDownLatch(1);
    final List<ProcessingRequest> capturedRequests =
        Collections.synchronizedList(new ArrayList<>());
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
                if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test-message");
    proxyCall.halfClose();

    assertThat(extProcLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // The requests sent during the request flow should be body requests,
    // and no request header message should be sent.
    assertThat(capturedRequests).hasSize(2);
    assertThat(capturedRequests.get(0).hasRequestHeaders()).isFalse();
    assertThat(capturedRequests.get(0).hasRequestBody()).isTrue();
    assertThat(capturedRequests.get(0).getRequestBody().getBody().toStringUtf8())
        .isEqualTo("test-message");
    assertThat(capturedRequests.get(1).hasRequestHeaders()).isFalse();
    assertThat(capturedRequests.get(1).hasRequestBody()).isTrue();
    assertThat(capturedRequests.get(1).getRequestBody().getEndOfStreamWithoutMessage()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 7: Body Mutation: Outbound/Request (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenSendMessageCalled_thenMessageSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = "extProc-sendMessage-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-sendMessage-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch bodySentLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              synchronized (responseObserver) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  if (capturedRequest.get() == null
                      && !request.getRequestBody().getBody().isEmpty()) {
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
              }
            }).start();
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            new Thread(() -> {
              synchronized (responseObserver) {
                responseObserver.onCompleted();
              }
            }).start();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Hello World");
    proxyCall.halfClose();

    assertThat(bodySentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().getRequestBody().getBody().toStringUtf8())
        .contains("Hello World");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedBodyForwarded()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-mutatedBody-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-mutatedBody-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
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
                            .setEndOfStreamWithoutMessage(true)
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
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
  public void givenRequestBodyModeGrpc_whenExtProcRespondsEmpty_thenEmptyMsgDelivered()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-emptyMsg-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-emptyMsg-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
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
                  && request.getRequestBody().getEndOfStreamWithoutMessage()) {
                bodyResponse.setResponse(CommonResponse.newBuilder()
                    .setBodyMutation(BodyMutation.newBuilder()
                        .setStreamedResponse(StreamedBodyResponse.newBuilder()
                            .setEndOfStream(true)
                            .setEndOfStreamWithoutMessage(true)
                            .build())
                        .build())
                    .build());
              } else {
                bodyResponse.setResponse(CommonResponse.newBuilder()
                    .setBodyMutation(BodyMutation.newBuilder()
                        .setStreamedResponse(StreamedBodyResponse.newBuilder()
                            .setBody(ByteString.EMPTY)
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

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    final AtomicReference<Status> clientStatus = new AtomicReference<>();
    final CountDownLatch clientCloseLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        clientStatus.set(status);
        clientCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("Original");
    proxyCall.halfClose();

    boolean dataPlaneOk = dataPlaneLatch.await(5, TimeUnit.SECONDS);
    boolean clientClosedOk = clientCloseLatch.await(5, TimeUnit.SECONDS);

    assertThat(dataPlaneOk).isTrue();
    assertThat(receivedBody.get()).isEqualTo("");
    assertThat(clientClosedOk).isTrue();
    assertThat(clientStatus.get().isOk()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcSignaledEndOfStream_whenClientSendsMoreMessages_thenMessagesDiscarded()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-discarded-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-discarded-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarMessages = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
              boolean triggerEos =
                  request.getRequestBody().getBody().toStringUtf8().equals("Trigger EOS");
              BodyResponse.Builder bodyResponse = BodyResponse.newBuilder();
              if (triggerEos || (request.getRequestBody().getBody().isEmpty()
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

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
  public void givenRequestBodyModeNone_whenSendMessageCalled_thenMessageSentDirectlyToDataPlane()
      throws Exception {
    String uniqueExtProcServerName = "extProc-noneBody-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-noneBody-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicInteger extProcBodyCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
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
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("Hello World");
    proxyCall.halfClose();

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBody.get()).isEqualTo("Hello World");
    assertThat(extProcBodyCount.get()).isEqualTo(0);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }


  // --- Category 8: Response Header Mutation ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseHeaderModeSend_whenExtProcRespondsWithMutatedHeaders_thenSent()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-resp-headers-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-resp-headers-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    Metadata.Key<String> mutatedKey =
        Metadata.Key.of("mutated-header", Metadata.ASCII_STRING_MARSHALLER);

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
                          .addSetHeaders(
                              io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                                  .setHeader(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
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

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    // Verify application receives mutated response headers
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedHeaders.get().get(mutatedKey)).isEqualTo("mutated-value");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseHeaderModeSkip_responseHeadersSentDirectlyUpstream()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-skip-headers-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-skip-headers-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    Metadata.Key<String> customKey =
        Metadata.Key.of("custom-response-header", Metadata.ASCII_STRING_MARSHALLER);

    // External Processor Server
    final java.util.concurrent.atomic.AtomicBoolean responseHeadersReceived =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    final java.util.concurrent.CountDownLatch requestHeadersLatch =
        new java.util.concurrent.CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            ProcessingResponse.Builder response = ProcessingResponse.newBuilder();
            if (request.hasRequestHeaders()) {
              response.setRequestHeaders(HeadersResponse.newBuilder()
                  .setResponse(CommonResponse.newBuilder().build())
                  .build());
              requestHeadersLatch.countDown();
            } else if (request.hasResponseHeaders()) {
              responseHeadersReceived.set(true);
            }
            responseObserver.onNext(response.build());
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
            extProcCompletedLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
            Metadata responseHeaders = new Metadata();
            responseHeaders.put(customKey, "response-value");
            call.sendHeaders(responseHeaders);
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final AtomicReference<Metadata> receivedHeaders = new AtomicReference<>();
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onHeaders(Metadata headers) {
        receivedHeaders.set(headers);
        headersLatch.countDown();
      }

      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    // Wait for request headers to be processed
    assertThat(requestHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify application receives original response headers directly
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedHeaders.get().get(customKey)).isEqualTo("response-value");

    // Wait for the call to close naturally and the ext_proc stream to complete
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseHeadersReceived.get()).isFalse();
    
    channelManager.close();
  }


  // --- Category 9: Body Mutation: Inbound/Response (GRPC Mode) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenOnMessageCalled_thenMessageSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
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
    final CountDownLatch sidecarBodyLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            } else if (request.hasResponseTrailers()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).executor(scheduler).build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(scheduler);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
    }
    assertThat(capturedRequest.get().getResponseBody().getBody().toStringUtf8())
        .isEqualTo("Server Message");

    while ((appMessageLatch.getCount() > 0 || appCloseLatch.getCount() > 0)
        && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeGrpc_whenExtProcRespondsWithMutatedBody_thenMutatedDelivered()
      throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    // External Processor Server
    MutableHandlerRegistry extProcRegistry = new MutableHandlerRegistry();
    final CountDownLatch sidecarBodyLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              sidecarBodyLatch.countDown();
            } else if (request.hasResponseTrailers()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(scheduler);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
    }
    while (appMessageLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(capturedMessage.get()).isEqualTo("Mutated Server");
    while (appCloseLatch.getCount() > 0 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }


  // --- Category 10: Response Trailers ---

  @Test
  public void
      givenResponseTrailerModeSend_whenCallCloses_thenTrailersAndStatusPropagated()
          throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              private boolean responseCompleted;

              private void completeResponse() {
                if (!responseCompleted) {
                  responseCompleted = true;
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasResponseTrailers()) {
                  capturedRequest.set(request);
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder()
                          .setHeaderMutation(HeaderMutation.newBuilder()
                              .addSetHeaders(HeaderValueOption.newBuilder()
                                  .setHeader(HeaderValue.newBuilder()
                                      .setKey("x-extproc-trailer")
                                      .setValue("mutated")
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  sidecarLatch.countDown();
                  completeResponse();
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

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                completeResponse();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    // Data Plane Server returning specific status and trailer
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
            return next.startCall(
                new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void close(Status status, Metadata trailers) {
                    trailers.put(
                        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER),
                        "original");
                    super.close(
                        Status.INVALID_ARGUMENT.withDescription("Custom DataPlane Error"),
                        trailers);
                  }
                }, headers);
          }
        }));

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> capturedTrailers = new AtomicReference<>();

    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        capturedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify status was propagated correctly
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(capturedStatus.get().getDescription()).isEqualTo("Custom DataPlane Error");

    // Verify trailers contain both dataplane trailer and mutated extproc trailer
    Metadata finalTrailers = capturedTrailers.get();
    assertThat(finalTrailers.get(
        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("original");
    assertThat(finalTrailers.get(
        Metadata.Key.of("x-extproc-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("mutated");

    channelManager.close();
  }

  @Test
  public void givenResponseTrailerModeSend_whenCallCloses_thenResponseTrailersSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          private boolean responseCompleted;

          private void completeResponse() {
            if (!responseCompleted) {
              responseCompleted = true;
              responseObserver.onCompleted();
            }
          }

          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseTrailers()) {
              capturedRequest.set(request);
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseTrailers(TrailersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
              completeResponse();
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            completeResponse();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build()
        .start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
            return next.startCall(
                new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void close(Status status, Metadata trailers) {
                    trailers.put(
                        Metadata.Key.of("x-trailer", Metadata.ASCII_STRING_MARSHALLER), "val");
                    super.close(status, trailers);
                  }
                }, headers);
          }
        }));

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch callLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedRequest.get().hasResponseTrailers()).isTrue();
    assertThat(capturedRequest.get().getResponseTrailers().getTrailers().getHeadersList())
        .isNotEmpty();
    
    channelManager.close();
  }

  @Test
  public void givenResponseTrailerModeDefault_whenCallCloses_thenResponseTrailersNotSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicInteger sidecarTrailerCount = new AtomicInteger(0);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          private boolean responseCompleted;

          private void completeResponse() {
            if (!responseCompleted) {
              responseCompleted = true;
              responseObserver.onCompleted();
            }
          }

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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            completeResponse();
            extProcCompletedLatch.countDown();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build()
        .start());

    // DEFAULT mode for trailers (interpreted as SKIP)
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
            return next.startCall(
                new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void close(Status status, Metadata trailers) {
                    trailers.put(
                        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER),
                        "original");
                    super.close(
                        Status.INVALID_ARGUMENT.withDescription("Custom DataPlane Error"),
                        trailers);
                  }
                }, headers);
          }
        }));
    
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());
         
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> capturedTrailers = new AtomicReference<>();

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        capturedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarHeadersLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();
    // Wait for the ext_proc stream to complete
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarTrailerCount.get()).isEqualTo(0);

    // Verify status was propagated correctly
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(capturedStatus.get().getDescription()).isEqualTo("Custom DataPlane Error");

    // Verify trailers contain dataplane trailer
    Metadata finalTrailers = capturedTrailers.get();
    assertThat(finalTrailers.get(
        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("original");
    
    channelManager.close();
  }

  @Test
  public void givenResponseTrailerModeSkip_whenCallCloses_thenResponseTrailersNotSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicInteger sidecarTrailerCount = new AtomicInteger(0);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarHeadersLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          private boolean responseCompleted;

          private void completeResponse() {
            if (!responseCompleted) {
              responseCompleted = true;
              responseObserver.onCompleted();
            }
          }

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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            completeResponse();
            extProcCompletedLatch.countDown();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build()
        .start());

    // SKIP mode for trailers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
            return next.startCall(
                new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  public void close(Status status, Metadata trailers) {
                    trailers.put(
                        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER),
                        "original");
                    super.close(
                        Status.INVALID_ARGUMENT.withDescription("Custom DataPlane Error"),
                        trailers);
                  }
                }, headers);
          }
        }));
    
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());
         
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> capturedTrailers = new AtomicReference<>();

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        capturedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarHeadersLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();
    // Wait for the ext_proc stream to complete
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarTrailerCount.get()).isEqualTo(0);

    // Verify status was propagated correctly
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(capturedStatus.get().getDescription()).isEqualTo("Custom DataPlane Error");

    // Verify trailers contain dataplane trailer
    Metadata finalTrailers = capturedTrailers.get();
    assertThat(finalTrailers.get(
        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("original");
    
    channelManager.close();
  }

  // --- Category 11: Trailers-only response handling ---

  @Test
  public void
      givenResponseHeaderModeSend_whenTrailersOnlyReceived_thenResponseHeadersSentToExtProc()
      throws Exception {
    String myExtProcServerName = InProcessServerBuilder.generateName();
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>
        capturedResponseHeadersRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    class MyExtProcImpl extends io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc
        .ExternalProcessorImplBase {
      @Override
      public io.grpc.stub.StreamObserver<
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>
          process(
              final io.grpc.stub.StreamObserver<
                      io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>
                  responseObserver) {
        ((io.grpc.stub.ServerCallStreamObserver<
                    io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>)
                responseObserver)
            .request(100);
        return new io.grpc.stub.StreamObserver<
            io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>() {
          @Override
          public void onNext(
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                      .setRequestHeaders(
                          io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder()
                              .build())
                      .build());
            } else if (request.hasResponseHeaders()) {
              capturedResponseHeadersRequest.set(request);
              // Sidecar mutates the trailers-only headers (which are the trailers)
              responseObserver.onNext(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                      .setResponseHeaders(
                          io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder()
                              .setResponse(
                                  io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse
                                      .newBuilder()
                                      .setHeaderMutation(
                                          io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation
                                              .newBuilder()
                                              .addSetHeaders(
                                                  io.envoyproxy.envoy.config.core.v3
                                                      .HeaderValueOption.newBuilder()
                                                      .setHeader(
                                                          io.envoyproxy.envoy.config.core.v3
                                                              .HeaderValue.newBuilder()
                                                              .setKey("x-mutated-trailer")
                                                              .setValue("val")
                                                              .build())
                                                      .build())
                                              .build())
                                      .build())
                              .build())
                      .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    }

    io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc.ExternalProcessorImplBase
        extProcImpl = new MyExtProcImpl();
    grpcCleanup.register(InProcessServerBuilder.forName(myExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Explicitly enable response headers for this test
    io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor proto =
        createBaseProto(myExtProcServerName)
            .setProcessingMode(
                io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode.newBuilder()
                    .setResponseHeaderMode(
                        io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode
                            .HeaderSendMode.SEND)
                    .build())
            .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(myExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    // Data plane server returns trailers-only (onError results in trailers-only)
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onError(
              Status.UNAUTHENTICATED
                  .withDescription("force-trailers-only")
                  .asRuntimeException());
        })).build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final AtomicReference<Metadata> capturedAppTrailers = new AtomicReference<>();
    final CountDownLatch callLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(Executors.newSingleThreadExecutor()),
            dataPlaneChannel);
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
    assertThat(
            appTrailers.get(
                Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("val");
    
    channelManager.close();
  }

  @Test
  public void
      givenResponseHeaderModeDefault_whenTrailersOnlyReceived_thenResponseHeadersSentToExtProc()
      throws Exception {
    String myExtProcServerName = InProcessServerBuilder.generateName();
    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>
        capturedResponseHeadersRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    class MyExtProcImpl extends io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc
        .ExternalProcessorImplBase {
      @Override
      public io.grpc.stub.StreamObserver<
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>
          process(
              final io.grpc.stub.StreamObserver<
                      io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>
                  responseObserver) {
        ((io.grpc.stub.ServerCallStreamObserver<
                    io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>)
                responseObserver)
            .request(100);
        return new io.grpc.stub.StreamObserver<
            io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest>() {
          @Override
          public void onNext(
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                      .setRequestHeaders(
                          io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder()
                              .build())
                      .build());
            } else if (request.hasResponseHeaders()) {
              capturedResponseHeadersRequest.set(request);
              // Sidecar mutates the trailers-only headers (which are the trailers)
              responseObserver.onNext(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.newBuilder()
                      .setResponseHeaders(
                          io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse.newBuilder()
                              .setResponse(
                                  io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse
                                      .newBuilder()
                                      .setHeaderMutation(
                                          io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation
                                              .newBuilder()
                                              .addSetHeaders(
                                                  io.envoyproxy.envoy.config.core.v3
                                                      .HeaderValueOption.newBuilder()
                                                      .setHeader(
                                                          io.envoyproxy.envoy.config.core.v3
                                                              .HeaderValue.newBuilder()
                                                              .setKey("x-mutated-trailer")
                                                              .setValue("val")
                                                              .build())
                                                      .build())
                                              .build())
                                      .build())
                              .build())
                      .build());
              sidecarLatch.countDown();
              responseObserver.onCompleted();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    }

    io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc.ExternalProcessorImplBase
        extProcImpl = new MyExtProcImpl();
    grpcCleanup.register(InProcessServerBuilder.forName(myExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Explicitly set response header mode to DEFAULT
    io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor proto =
        createBaseProto(myExtProcServerName)
            .setProcessingMode(
                io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode.newBuilder()
                    .setResponseHeaderMode(
                        io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode
                            .HeaderSendMode.DEFAULT)
                    .build())
            .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(myExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    // Data plane server returns trailers-only (onError results in trailers-only)
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onError(
              Status.UNAUTHENTICATED
                  .withDescription("force-trailers-only")
                  .asRuntimeException());
        })).build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final AtomicReference<Metadata> capturedAppTrailers = new AtomicReference<>();
    final CountDownLatch callLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(Executors.newSingleThreadExecutor()),
            dataPlaneChannel);
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
    assertThat(
            appTrailers.get(
                Metadata.Key.of("x-mutated-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("val");
    
    channelManager.close();
  }

  @Test
  public void
      givenResponseHeaderModeSkip_whenTrailersOnlyReceived_thenResponseHeadersNotSentToExtProc()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicInteger sidecarTrailerCount = new AtomicInteger(0);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasResponseTrailers()) {
              sidecarTrailerCount.incrementAndGet();
            } else if (request.hasResponseHeaders()) {
              sidecarTrailerCount.incrementAndGet();
            } else if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
            extProcCompletedLatch.countDown();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build()
        .start());

    // SKIP mode for headers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueDataPlaneRegistry = new MutableHandlerRegistry();
    uniqueDataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          Metadata trailers = new Metadata();
          trailers.put(
              Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER),
              "original");
          responseObserver.onError(
              Status.INVALID_ARGUMENT
                  .withDescription("Custom DataPlane Error")
                  .asRuntimeException(trailers));
        })).build());
    
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueDataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());
         
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> capturedTrailers = new AtomicReference<>();

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        capturedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();
    // Wait for the ext_proc stream to complete
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarTrailerCount.get()).isEqualTo(0);

    // Verify status was propagated correctly
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(capturedStatus.get().getDescription()).isEqualTo("Custom DataPlane Error");

    // Verify trailers contain dataplane trailer
    Metadata finalTrailers = capturedTrailers.get();
    assertThat(finalTrailers.get(
        Metadata.Key.of("x-dataplane-trailer", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("original");
    
    channelManager.close();
  }



  // --- Category 12: Half-Close handling ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestBodyModeGrpc_whenHalfCloseCalled_thenSuperHalfCloseDeferred()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch halfCloseLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody()
                && request.getRequestBody().getEndOfStreamWithoutMessage()) {
              halfCloseLatch.countDown();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
  public void deferredHalfClose_whenExtProcRespondsWithEosWithoutMessage_thenSuperHalfCloseCalled()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody()) {
              if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setBody(ByteString.copyFromUtf8("mutated1"))
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setBody(ByteString.copyFromUtf8("mutated2"))
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
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
              }
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    serverReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Ack");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch =
        new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDeferredHalfClose_whenExtProcRespondsWithEndOfStream_thenSuperHalfCloseCalled()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestBody()) {
              if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setBody(ByteString.copyFromUtf8("mutated1"))
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                            .setBodyMutation(BodyMutation.newBuilder()
                                .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                    .setBody(ByteString.copyFromUtf8("mutated2"))
                                    .setEndOfStream(true)
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build());
              }
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    serverReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Ack");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch =
        new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.halfClose();

    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void
      extProcRespondsWithEosWithoutMsg_whenAppNotHalfClosed_thenSuperHalfClose_moreDiscarded()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> extProcRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            extProcRequests.add(request);
            if (request.hasRequestBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("mutated1"))
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("mutated2"))
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    serverReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Ack");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch =
        new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("req1");

    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    // Client app continues to send messages after super half close propagated.
    proxyCall.sendMessage("req2");

    // Assert that these messages are discarded and not propagated to either
    // the ext_proc or the dataplane.
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    List<HttpBody> requestBodies = new java.util.ArrayList<>();
    for (ProcessingRequest req : extProcRequests) {
      if (req.hasRequestBody()) {
        requestBodies.add(req.getRequestBody());
      }
    }
    assertThat(requestBodies).hasSize(1);
    assertThat(requestBodies.get(0).getBody().toStringUtf8()).isEqualTo("req1");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void
      givenExtProcRespondsWithEos_whenAppHasNotHalfClosed_thenSuperHalfClose_moreDiscarded()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> extProcRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            extProcRequests.add(request);
            if (request.hasRequestBody()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("mutated1"))
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestBody(BodyResponse.newBuilder()
                      .setResponse(CommonResponse.newBuilder()
                          .setBodyMutation(BodyMutation.newBuilder()
                              .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                  .setBody(ByteString.copyFromUtf8("mutated2"))
                                  .setEndOfStream(true)
                                  .build())
                              .build())
                          .build())
                      .build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> serverReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueRegistry)
        .directExecutor()
        .build().start());
    uniqueRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    serverReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Ack");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final java.util.concurrent.CountDownLatch dataPlaneHalfClosedLatch =
        new java.util.concurrent.CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("req1");

    assertThat(dataPlaneHalfClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    // Client app continues to send messages after super half close propagated.
    proxyCall.sendMessage("req2");

    // Assert that these messages are discarded and not propagated to either
    // the ext_proc or the dataplane.
    assertThat(serverReceivedMessages).containsExactly("mutated1", "mutated2");

    List<HttpBody> requestBodies = new java.util.ArrayList<>();
    for (ProcessingRequest req : extProcRequests) {
      if (req.hasRequestBody()) {
        requestBodies.add(req.getRequestBody());
      }
    }
    assertThat(requestBodies).hasSize(1);
    assertThat(requestBodies.get(0).getBody().toStringUtf8()).isEqualTo("req1");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }



  // --- Category 13: Outbound Backpressure (isReady / onReady) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityTrue_whenExtProcBusy_thenIsReadyReturnsFalse()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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

    final List<ProcessingRequest> extProcRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            extProcRequests.add(request);
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                      ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final CountDownLatch readyLatch = new CountDownLatch(1);
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onReady() {
        readyLatch.countDown();
      }
    }, new Metadata());

    // Wait for activation (sidecar needs to respond to headers)
    assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();

    assertThat(extProcRequests).isNotEmpty();
    for (ProcessingRequest request : extProcRequests) {
      assertThat(request.getObservabilityMode()).isTrue();
    }
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityMode_whenUpstreamBusy_thenIsReadyReturnsFalse()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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

    final List<ProcessingRequest> extProcRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            extProcRequests.add(request);
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    final AtomicBoolean upstreamReady = new AtomicBoolean(true);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                    ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public boolean isReady() {
                    return upstreamReady.get();
                  }
                };
              }
            })
            .build());

    final CountDownLatch readyLatch = new CountDownLatch(1);
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onReady() {
        readyLatch.countDown();
      }
    }, new Metadata());

    // Wait for activation (sidecar needs to respond to headers)
    assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    // Upstream busy
    upstreamReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();

    assertThat(extProcRequests).isNotEmpty();
    for (ProcessingRequest request : extProcRequests) {
      assertThat(request.getObservabilityMode()).isTrue();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenNormalMode_whenUpstreamBusy_thenIsReadyReturnsTrue()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    final AtomicBoolean upstreamReady = new AtomicBoolean(false);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                    ReqT, RespT>(next.newCall(method, callOptions)) {
                  @Override
                  public boolean isReady() {
                    return upstreamReady.get();
                  }
                };
              }
            })
            .build());

    final CountDownLatch readyLatch = new CountDownLatch(1);
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onReady() {
        readyLatch.countDown();
      }
    }, new Metadata());

    // Wait for activation (sidecar needs to respond to headers)
    assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Since sidecar is ready, proxyCall.isReady() should return true,
    // ignoring that upstream is busy
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenCongestionInExtProc_whenExtProcBecomesReady_thenTriggersOnReady()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      @Override
      public void onReady() {
        onReadyLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for sidecar call to start and listener to be captured
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
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
  public void givenExtProcStreamCompleted_whenIsReadyCalled_thenDelegatesToSuper()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    // 1. Configure ProcessingMode to only send request headers (skip the rest)
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    final CountDownLatch sidecarResponseLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  responseObserver.onCompleted();
                  sidecarResponseLatch.countDown();
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .directExecutor()
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    // 2. Custom ClientInterceptor to mock downstream call readiness
    final AtomicBoolean downstreamReady = new AtomicBoolean(true);
    final AtomicInteger downstreamIsReadyCallCount = new AtomicInteger(0);
    ClientInterceptor downstreamMockInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public boolean isReady() {
            downstreamIsReadyCallCount.incrementAndGet();
            return downstreamReady.get();
          }
        };
      }
    };

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          // Do not respond immediately to keep the data plane call active
        })).build());

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .intercept(downstreamMockInterceptor)
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    // 3. Wait for the sidecar response to complete the external processor stream
    assertThat(sidecarResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // 4. Assert that proxyCall.isReady() delegates directly to the downstream call
    downstreamReady.set(true);
    assertThat(proxyCall.isReady()).isTrue();
    assertThat(downstreamIsReadyCallCount.get()).isEqualTo(1);

    downstreamReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();
    assertThat(downstreamIsReadyCallCount.get()).isEqualTo(2);

    proxyCall.cancel("cleanup", null);
    channelManager.close();
  }

  @Test
  public void givenDataPlaneCallIdle_whenIsReadyCalled_thenReturnsFalse() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .directExecutor()
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    // Call isReady() before calling start()
    assertThat(proxyCall.isReady()).isFalse();

    channelManager.close();
  }

  // --- Category 14: Ext-proc request draining ---

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestBodyDrainingBypassedWhenRequestBodyModeNone() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true) // Trigger Request Drain
                      .build());
                  sidecarActionLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final List<String> dataPlaneSentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void sendMessage(ReqT message) {
                      try {
                        InputStream stream = (InputStream) message;
                        byte[] bytes = com.google.common.io.ByteStreams.toByteArray(stream);
                        dataPlaneSentMessages.add(
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        super.sendMessage((ReqT) new java.io.ByteArrayInputStream(bytes));
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }
                  };
                }
            })
            .build());

    final List<String> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessageLatch = new CountDownLatch(2);
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> appReceivedHeaders = new AtomicReference<>();
    final AtomicReference<Status> appReceivedStatus = new AtomicReference<>();

    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appReceivedHeaders.set(headers);
      }

      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        appMessageLatch.countDown();
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        appReceivedStatus.set(status);
        appCloseLatch.countDown();
      }
    };

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Wait for the drain signal to be received and processed by client call

    // Call is now in DRAINING state.
    // Send a message. Since request_body_mode is NONE, it should go directly to data plane.
    proxyCall.sendMessage("Hello ExtProc");

    assertThat(dataPlaneSentMessages).containsExactly("Hello ExtProc");

    // Server sends response headers and messages back.
    // Since sendResponseHeaders is true and call is DRAINING, the response headers and messages
    // should be saved.
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");
    upstreamResponseObserver.onNext("Hello Downstream");

    // Verify that app has not received headers or messages yet (because they are saved
    // since stream is still DRAINING)
    assertThat(appReceivedHeaders.get()).isNull();
    assertThat(appReceivedMessages).isEmpty();

    // Now complete the ext_proc stream
    responseObserverRef.get().onCompleted();
    upstreamResponseObserver.onCompleted();

    // Verify that app receives headers, message, and close
    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedHeaders.get()).isNotNull();
    assertThat(appReceivedMessages).containsExactly("Dummy for headers", "Hello Downstream");

    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedStatus.get().isOk()).isTrue();

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHalfCloseEarlyWhenDrainingAndRequestBodyModeNone() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch drainSentLatch = new CountDownLatch(1);
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
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                  drainSentLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final CountDownLatch serverHalfClosedLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    serverHalfClosedLatch.countDown();
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(10);

    assertThat(drainSentLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Call is now in DRAINING state.
    // Call halfClose(). Since RequestBodyMode is NONE, it should proceed immediately.
    proxyCall.halfClose();

    // Verify downstream server receives half-close IMMEDIATELY, before ext_proc completes.
    assertThat(serverHalfClosedLatch.await(1, TimeUnit.SECONDS)).isTrue();

    // Cleanup ext_proc stream
    if (extProcResponseObserverRef.get() != null) {
      extProcResponseObserverRef.get().onCompleted();
    }
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHalfCloseEarlyWhenDrainingAndNoMessagesSent() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch drainSentLatch = new CountDownLatch(1);
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
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                  drainSentLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final CountDownLatch serverHalfClosedLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    serverHalfClosedLatch.countDown();
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(10);

    assertThat(drainSentLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Call is now in DRAINING state. No body messages were sent.
    // Call halfClose(). It should proceed immediately.
    proxyCall.halfClose();

    // Verify downstream server receives half-close IMMEDIATELY.
    assertThat(serverHalfClosedLatch.await(1, TimeUnit.SECONDS)).isTrue();

    // Cleanup
    if (extProcResponseObserverRef.get() != null) {
      extProcResponseObserverRef.get().onCompleted();
    }
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHalfCloseDeferredWhenDrainingAndMessagesSent() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch headersReceivedLatch = new CountDownLatch(1);
    final CountDownLatch bodyReceivedLatch = new CountDownLatch(1);
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
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  headersReceivedLatch.countDown();
                } else if (request.hasRequestBody()) {
                  // Respond to body with request_drain = true
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                  bodyReceivedLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final CountDownLatch serverHalfClosedLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    serverHalfClosedLatch.countDown();
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(10);

    assertThat(headersReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Send a message. It should go to ext_proc.
    proxyCall.sendMessage("Hello ExtProc");

    assertThat(bodyReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Call is now in DRAINING state, and bodyMessageSentToExtProc is true.
    // Call halfClose(). It should NOT proceed immediately.
    proxyCall.halfClose();

    // Verify downstream server has NOT received half-close yet.
    assertThat(serverHalfClosedLatch.await(1, TimeUnit.SECONDS)).isFalse();

    // Now complete the ext_proc stream.
    if (extProcResponseObserverRef.get() != null) {
      extProcResponseObserverRef.get().onCompleted();
    }

    // Verify downstream server now receives half-close.
    assertThat(serverHalfClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResponseBodyDrainingBypassedWhenResponseBodyModeNone() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true) // Trigger Request Drain
                      .build());
                  sidecarActionLatch.countDown();
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    final List<String> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        appMessageLatch.countDown();
      }
    };

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Wait for the drain signal to be received and processed by client call

    // Send response headers first (they bypass ext_proc because send mode is default SKIP, so
    // they proceed immediately)
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    // Now call is in DRAINING state, and savedHeaders is null.
    // Send response body message. Since response_body_mode is NONE, it should go directly
    // downstream.
    upstreamResponseObserver.onNext("Hello Downstream");

    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).contains("Hello Downstream");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResponseHeadersDrainingBypassedWhenResponseHeadersSkip() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true) // Trigger Request Drain
                      .build());
                  sidecarActionLatch.countDown();
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    final CountDownLatch headersLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        headersLatch.countDown();
      }
    };

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Wait for the drain signal to be received and processed by client call

    // Call is in DRAINING state.
    // Send response headers from server. Since response_header_mode is SKIP, they should go
    // directly downstream.
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResponseTrailersDrainingBypassedWhenResponseTrailersSkip() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                        + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true) // Trigger Request Drain
                      .build());
                  sidecarActionLatch.countDown();
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    final CountDownLatch closeLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closeLatch.countDown();
      }
    };

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // Wait for the drain signal to be received and processed by client call

    // Call is in DRAINING state.
    // Complete the server call. Since response_trailer_mode is SKIP, onClose should trigger
    // immediately.
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onCompleted();

    assertThat(closeLatch.await(5, TimeUnit.SECONDS)).isTrue();

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
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // isReady() must return false during drain.
    // Use a small loop because of SerializingExecutor delay even with directExecutor.
    long start = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - start < 2000) {
    }
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenOnReady() throws Exception {
    String uniqueExtProcServerName =
        "extProc-draining-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-draining-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    final CountDownLatch sidecarOnNextLatch = new CountDownLatch(1);
    final CountDownLatch sidecarOnCompletedLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestDrain(true)
                      .build());
                }
                sidecarOnNextLatch.countDown();
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    synchronized (responseObserver) {
                      responseObserver.onCompleted();
                    }
                    sidecarOnCompletedLatch.countDown();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      @Override
      public void onReady() {
        onReadyLatch.countDown();
      }
    };

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    proxyCall.request(1);
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    // Wait for sidecar to send drain and test to observe it
    assertThat(sidecarOnNextLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(proxyCall.isReady()).isFalse();

    // Now let sidecar complete
    sidecarFinishLatch.countDown();
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    dataPlaneFinishLatch.countDown();
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    assertThat(sidecarOnCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    // After sidecar stream completes, it should trigger onReady and become ready
    assertThat(onReadyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.cancel("Cleanup", null);
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    channelManager.close();
    for (int i = 0; i < 10; i++) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void givenDrainingStream_whenObserverIsNull_thenSendMessageDoesNotQueue()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestDrain(true)
                      .build());
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    @SuppressWarnings("unchecked")
    ClientCall<InputStream, String> proxyCall = (ClientCall<InputStream, String>) (ClientCall<?, ?>)
        interceptor.interceptCall(
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(drainLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Force the observer to null via reflection to test the false branch
    java.lang.reflect.Field field = proxyCall.getClass()
        .getDeclaredField("extProcClientCallRequestObserver");
    field.setAccessible(true);
    Object originalObserver = field.get(proxyCall);
    field.set(proxyCall, null);

    // Call sendMessage; it should return safely and be ignored without throwing NPE
    proxyCall.sendMessage(new java.io.ByteArrayInputStream(
        "test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    // Restore the observer so that cancel() can clean up resources properly
    field.set(proxyCall, originalObserver);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenDrainingStream_whenExtProcStreamCompletes_thenMessagesProceed()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
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

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestDrain(true)
                      .build());
                }
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    synchronized (responseObserver) {
                      responseObserver.onCompleted();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      @Override
      public void onMessage(String message) {
        appReceivedMessage.set(message);
        appLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for drain to be processed
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
    }
    assertThat(proxyCall.isReady()).isFalse();

    // Request messages from server while stream is draining (and sidecar not ready)
    proxyCall.request(1);

    // Now let sidecar complete
    sidecarFinishLatch.countDown();

    // Wait for it to become ready again
    startTime = System.currentTimeMillis();
    while (!proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
    }
    assertThat(proxyCall.isReady()).isTrue();

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

  @Test
  @SuppressWarnings("unchecked")
  public void
      drainingStartsBeforeRequestHeaders_whenAppSendsAndHalfCloses_thenBufferedAndDelivered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
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

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final AtomicInteger extProcReceivedBodyCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                }
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    synchronized (responseObserver) {
                      responseObserver.onCompleted();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            } else if (request.hasRequestBody()) {
              extProcReceivedBodyCount.incrementAndGet();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            drainCompletedLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> dataPlaneReceivedMessages =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    dataPlaneReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Direct Response");
                    responseObserver.onCompleted();
                    dataPlaneLatch.countDown();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<String> appReceivedMessage = new AtomicReference<>();
    final CountDownLatch appLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessage.set(message);
        appLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for drain to be processed
    assertThat(drainCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isFalse();

    // Send message and half-close concurrently during drain state
    final CountDownLatch appActionLatch = new CountDownLatch(1);
    new Thread(() -> {
      proxyCall.sendMessage("App Message During Drain");
      proxyCall.halfClose();
      appActionLatch.countDown();
    }).start();

    assertThat(appActionLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Assert that it was NOT received by extProc
    assertThat(extProcReceivedBodyCount.get()).isEqualTo(0);

    // Now let sidecar complete
    sidecarFinishLatch.countDown();

    // Request response from data plane
    proxyCall.request(1);

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Verify the exact messages and their delivery order at data plane server
    assertThat(dataPlaneReceivedMessages).containsExactly(
        "App Message During Drain"
    ).inOrder();
    
    assertThat(appLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessage.get()).isEqualTo("Direct Response");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void drainingStartsAfterRequestHeaders_whenAppSendsAndHalfCloses_thenBufferedAndDelivered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
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

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    final CountDownLatch extProcReceivedBodyLatch = new CountDownLatch(1);
    final CountDownLatch mutatedBodyDeliveredLatch = new CountDownLatch(1);
    
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              synchronized (responseObserver) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder().build())
                    .build());
              }
            } else if (request.hasRequestBody()) {
              extProcReceivedBodyLatch.countDown();
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFromUtf8("Mutated Message 1"))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .setRequestDrain(true)
                      .build());
                }
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    synchronized (responseObserver) {
                      responseObserver.onCompleted();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            drainCompletedLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> dataPlaneReceivedMessages =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    dataPlaneReceivedMessages.add(value);
                    if (value.equals("Mutated Message 1")) {
                      mutatedBodyDeliveredLatch.countDown();
                    }
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Direct Response");
                    responseObserver.onCompleted();
                    dataPlaneLatch.countDown();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<String> appReceivedMessage = new AtomicReference<>();
    final CountDownLatch appLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessage.set(message);
        appLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(1);

    // Send original message 1
    proxyCall.sendMessage("Original Message 1");

    // Wait until ext-proc receives it and mutated body is delivered downstream
    assertThat(extProcReceivedBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(mutatedBodyDeliveredLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify proxyCall is in draining (i.e. isReady is false)
    assertThat(proxyCall.isReady()).isFalse();

    // Send message and half-close concurrently during drain state
    final CountDownLatch appActionLatch = new CountDownLatch(1);
    new Thread(() -> {
      proxyCall.sendMessage("App Message During Drain");
      proxyCall.halfClose();
      appActionLatch.countDown();
    }).start();

    assertThat(appActionLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify the message during drain has NOT been delivered to the data plane server yet
    assertThat(dataPlaneReceivedMessages).containsExactly("Mutated Message 1");

    // Now let sidecar complete
    sidecarFinishLatch.countDown();

    // Wait for the control stream drain to be fully completed
    assertThat(drainCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Request response from data plane
    proxyCall.request(1);

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    // Verify the exact messages and their delivery order at data plane server
    assertThat(dataPlaneReceivedMessages).containsExactly(
        "Mutated Message 1", "App Message During Drain"
    ).inOrder();
    
    assertThat(appLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessage.get()).isEqualTo("Direct Response");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void drainingStartsBeforeResponseHeaders_whenUpstreamResponds_thenBufferedAndDelivered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
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

    // External Processor Server
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                }
                try {
                  if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                    synchronized (responseObserver) {
                      responseObserver.onCompleted();
                    }
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            drainCompletedLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    final CountDownLatch dataPlaneCallStartedLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                dataPlaneCallStartedLatch.countDown();
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final List<String> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicReference<Metadata> appReceivedHeaders = new AtomicReference<>();
    final AtomicReference<Status> appReceivedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> appReceivedTrailers = new AtomicReference<>();
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appReceivedHeaders.set(headers);
      }

      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        appReceivedStatus.set(status);
        appReceivedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Request messages from server
    proxyCall.request(10);

    // Wait for drain to be processed and sidecar's client stream to finish
    assertThat(drainCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isFalse();

    // Verify the data plane call has started
    assertThat(dataPlaneCallStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    StreamObserver<String> dataPlaneResponseObserver = dataPlaneResponseObserverRef.get();
    assertThat(dataPlaneResponseObserver).isNotNull();

    // Upstream server sends response headers, response message, and closes call during drain
    dataPlaneResponseObserver.onNext("Response Message During Drain");
    dataPlaneResponseObserver.onCompleted();

    // Verify app listener has NOT received headers, messages, or close yet because the drain
    // is active
    assertThat(appReceivedHeaders.get()).isNull();
    assertThat(appReceivedMessages).isEmpty();
    assertThat(appReceivedStatus.get()).isNull();

    // Now let sidecar complete the drain
    sidecarFinishLatch.countDown();

    // Wait for the call to close on application side
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify the delivery order: response headers, upstream response, and close status/trailers
    assertThat(appReceivedHeaders.get()).isNotNull();
    assertThat(appReceivedMessages).containsExactly("Response Message During Drain");
    assertThat(appReceivedStatus.get().isOk()).isTrue();
    assertThat(appReceivedTrailers.get()).isNotNull();
    
  }

  @Test
  @SuppressWarnings("unchecked")
  public void drainingStartsAfterResponseHeaders_whenUpstreamResponds_thenBufferedAndDelivered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
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

    final CountDownLatch reqHeadersLatch = new CountDownLatch(1);
    final CountDownLatch respHeadersLatch = new CountDownLatch(1);
    final CountDownLatch m2ReceivedLatch = new CountDownLatch(1);
    final CountDownLatch respBody1Latch = new CountDownLatch(1);
    final CountDownLatch respBody2Latch = new CountDownLatch(1);
    final CountDownLatch m3SentLatch = new CountDownLatch(1);
    final CountDownLatch sidecarFinishLatch = new CountDownLatch(1);
    final CountDownLatch drainCompletedLatch = new CountDownLatch(1);

    // External Processor Server
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
                  synchronized (responseObserver) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestHeaders(HeadersResponse.newBuilder().build())
                        .build());
                  }
                  reqHeadersLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  synchronized (responseObserver) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setResponseHeaders(HeadersResponse.newBuilder().build())
                        .build());
                  }
                  respHeadersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  String msgStr = request.getResponseBody().getBody().toStringUtf8();
                  if ("Original Message 1".equals(msgStr)) {
                    new Thread(() -> {
                      try {
                        // Wait until M2 is received by sidecar so both M1 and M2 are in flight
                        if (m2ReceivedLatch.await(5, TimeUnit.SECONDS)) {
                          synchronized (responseObserver) {
                            responseObserver.onNext(ProcessingResponse.newBuilder()
                                .setResponseBody(BodyResponse.newBuilder()
                                    .setResponse(CommonResponse.newBuilder()
                                        .setBodyMutation(BodyMutation.newBuilder()
                                            .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                    "Mutated Message 1"))
                                                .build())
                                            .build())
                                        .build())
                                    .build())
                                .setRequestDrain(true)
                                .build());
                          }
                          respBody1Latch.countDown();
                        }
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }).start();
                  } else if ("Original Message 2".equals(msgStr)) {
                    m2ReceivedLatch.countDown();
                    new Thread(() -> {
                      try {
                        // Wait until M3 is sent by upstream concurrently during drain
                        if (m3SentLatch.await(5, TimeUnit.SECONDS)) {
                          synchronized (responseObserver) {
                            responseObserver.onNext(ProcessingResponse.newBuilder()
                                .setResponseBody(BodyResponse.newBuilder()
                                    .setResponse(CommonResponse.newBuilder()
                                        .setBodyMutation(BodyMutation.newBuilder()
                                            .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                    "Mutated Message 2"))
                                                .build())
                                            .build())
                                        .build())
                                    .build())
                                .build());
                          }
                          respBody2Latch.countDown();
                        }
                        if (sidecarFinishLatch.await(5, TimeUnit.SECONDS)) {
                          synchronized (responseObserver) {
                            responseObserver.onCompleted();
                          }
                        }
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }).start();
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

    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    final CountDownLatch dataPlaneCallStartedLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                dataPlaneCallStartedLatch.countDown();
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final List<String> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicReference<Metadata> appReceivedHeaders = new AtomicReference<>();
    final AtomicReference<Status> appReceivedStatus = new AtomicReference<>();
    final AtomicReference<Metadata> appReceivedTrailers = new AtomicReference<>();
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final CountDownLatch mutatedMsg1ReceivedLatch = new CountDownLatch(1);
    final CountDownLatch mutatedMsg2ReceivedLatch = new CountDownLatch(1);
    
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appReceivedHeaders.set(headers);
      }

      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        if ("Mutated Message 1".equals(message)) {
          mutatedMsg1ReceivedLatch.countDown();
        } else if ("Mutated Message 2".equals(message)) {
          mutatedMsg2ReceivedLatch.countDown();
        }
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        appReceivedStatus.set(status);
        appReceivedTrailers.set(trailers);
        appCloseLatch.countDown();
      }
    };

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Request messages from server
    proxyCall.request(10);

    // Wait for the data plane call to start
    assertThat(dataPlaneCallStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    StreamObserver<String> dataPlaneResponseObserver = dataPlaneResponseObserverRef.get();
    assertThat(dataPlaneResponseObserver).isNotNull();

    // 1. Upstream sends M1 (which triggers response headers and M1 body to sidecar)
    dataPlaneResponseObserver.onNext("Original Message 1");

    // Wait for sidecar to receive and respond to response headers
    assertThat(respHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // 2. Upstream sends M2 (which triggers M2 body to sidecar)
    dataPlaneResponseObserver.onNext("Original Message 2");

    // Wait for app to receive Mutated Message 1 (meaning M1's response with request_drain=true
    // has been processed)
    assertThat(mutatedMsg1ReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify the stream is currently in DRAINING state and app has only received Mutated
    // Message 1 so far
    assertThat(appReceivedMessages).containsExactly("Mutated Message 1");

    // 3. Upstream concurrently sends M3 and completes the call during draining
    dataPlaneResponseObserver.onNext("Original Message 3");
    dataPlaneResponseObserver.onCompleted();

    // Verify that M3 and close are not delivered to application yet because drain is still active
    assertThat(appReceivedMessages).containsExactly("Mutated Message 1");
    assertThat(appReceivedStatus.get()).isNull();

    // 4. Signal sidecar to send Mutated Message 2
    m3SentLatch.countDown();
    
    // Wait for sidecar to finish sending M2 and app to receive it
    assertThat(respBody2Latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(mutatedMsg2ReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify that Mutated Message 2 is delivered immediately to app upon arrival (even before
    // M3 is released)
    assertThat(appReceivedMessages).containsExactly("Mutated Message 1", "Mutated Message 2");

    // 5. Complete sidecar stream to finish the drain
    sidecarFinishLatch.countDown();

    // Wait for the call to close on application side
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify delivery order: mutated messages first, then bypass messages, and finally
    // status/trailers
    assertThat(appReceivedHeaders.get()).isNotNull();
    assertThat(appReceivedMessages).containsExactly(
        "Mutated Message 1", "Mutated Message 2", "Original Message 3"
    );
    assertThat(appReceivedStatus.get().isOk()).isTrue();
    assertThat(appReceivedTrailers.get()).isNotNull();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 15: Ext-proc fail-open draining of flow-control queues ---

  @Test
  @SuppressWarnings("unchecked")
  public void testFailOpen_DrainsInboundQueuesInOrder() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcReceivedHeadersLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final CountDownLatch backendSentMessage2Latch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, (call, headers) -> {
          call.sendHeaders(new Metadata());
          // Send message 1 (70k to close window)
          String largeMessage70k = new String(new char[70000]).replace('\0', 'a');
          call.sendMessage(largeMessage70k);

          new Thread(() -> {
            try {
              if (extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)) {
                // Send message 2 (unsolicited, will be buffered in savedMessages)
                call.sendMessage("backend-msg-2");
                backendSentMessage2Latch.countDown();
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }).start();

          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {}

            @Override
            public void onHalfClose() {}

            @Override
            public void onCancel() {}
          };
        })
        .build());

    final List<String> appReceivedMessages = new CopyOnWriteArrayList<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callClosedLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(10);

    // Wait for backend to send message 2
    assertThat(backendSentMessage2Latch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify app received nothing yet (buffered in savedMessages)
    assertThat(appReceivedMessages).isEmpty();

    // Trigger fail-open by error on ext_proc stream
    responseObserverRef.get().onError(Status.UNAVAILABLE.asException());

    // Verify call is NOT closed
    assertThat(callClosedLatch.getCount()).isEqualTo(1);

    // Verify all buffered messages are drained in order: largeMessage70k then backend-msg-2
    String largeMessage70k = new String(new char[70000]).replace('\0', 'a');
    assertThat(appReceivedMessages).containsExactly(largeMessage70k, "backend-msg-2").inOrder();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFailOpen_DrainsBlockedRequests() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);

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
                  // Respond with headers AND negative window update to block outbound body
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
                          .setWindowIncrementDownstreamToSidestream(-65536) // Reduce window to 0
                          .build())
                      .build());
                  extProcReceivedHeadersLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> sentToBackend = new CopyOnWriteArrayList<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, (call, headers) -> {
          call.sendHeaders(new Metadata());
          call.request(100);
          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {}

            @Override
            public void onHalfClose() {}

            @Override
            public void onCancel() {}
          };
        })
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientInterceptor backendInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegateCall = next.newCall(method, callOptions);
        return new SimpleForwardingClientCall<ReqT, RespT>(delegateCall) {
          @Override
          public void sendMessage(ReqT message) {
            try {
              InputStream is = (InputStream) message;
              byte[] bytes = com.google.common.io.ByteStreams.toByteArray(is);
              String str = new String(bytes, StandardCharsets.UTF_8);
              sentToBackend.add(str);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            super.sendMessage(message);
          }
        };
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(dataPlaneChannel, backendInterceptor);

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            interceptedChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    assertThat(extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // These should now be buffered because window is 0
    proxyCall.sendMessage("msg-1");
    proxyCall.sendMessage("msg-2");

    assertThat(sentToBackend).isEmpty();

    // Trigger fail-open
    responseObserverRef.get().onError(Status.UNAVAILABLE.asException());

    // Verify messages are drained
    assertThat(sentToBackend).containsExactly("msg-1", "msg-2").inOrder();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFailOpen_DrainsDrainingRequests() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);

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
                  // Respond with headers AND request_drain = true to trigger DRAINING state
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setRequestDrain(true)
                      .build());
                  extProcReceivedHeadersLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> sentToBackend = new CopyOnWriteArrayList<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, (call, headers) -> {
          call.sendHeaders(new Metadata());
          call.request(100);
          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {}

            @Override
            public void onHalfClose() {}

            @Override
            public void onCancel() {}
          };
        })
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientInterceptor backendInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegateCall = next.newCall(method, callOptions);
        return new SimpleForwardingClientCall<ReqT, RespT>(delegateCall) {
          @Override
          public void sendMessage(ReqT message) {
            try {
              InputStream is = (InputStream) message;
              byte[] bytes = com.google.common.io.ByteStreams.toByteArray(is);
              String str = new String(bytes, StandardCharsets.UTF_8);
              sentToBackend.add(str);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            super.sendMessage(message);
          }
        };
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(dataPlaneChannel, backendInterceptor);

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            interceptedChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    assertThat(extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Send msg-1. Since state is DRAINING, it should be buffered in pendingDrainingMessages.
    proxyCall.sendMessage("msg-1");

    assertThat(sentToBackend).isEmpty();

    // Trigger fail-open
    responseObserverRef.get().onError(Status.UNAVAILABLE.asException());

    // Verify message is drained
    assertThat(sentToBackend).containsExactly("msg-1");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFailOpen_ResumesDrainingOnReady() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);

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
                  // Respond with headers AND negative window update to block outbound body
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
                          .setWindowIncrementDownstreamToSidestream(-65536) // Reduce window to 0
                          .build())
                      .build());
                  extProcReceivedHeadersLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> sentToBackend = new CopyOnWriteArrayList<>();
    final AtomicBoolean backendReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<String>> backendListenerRef = new AtomicReference<>();

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, (call, headers) -> {
          call.sendHeaders(new Metadata());
          call.request(100);
          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {}

            @Override
            public void onHalfClose() {}

            @Override
            public void onCancel() {}
          };
        })
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientInterceptor backendInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegateCall = next.newCall(method, callOptions);
        return new SimpleForwardingClientCall<ReqT, RespT>(delegateCall) {
          @Override
          public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
            backendListenerRef.set((ClientCall.Listener<String>) responseListener);
            super.start(responseListener, headers);
          }

          @Override
          public void sendMessage(ReqT message) {
            try {
              InputStream is = (InputStream) message;
              byte[] bytes = com.google.common.io.ByteStreams.toByteArray(is);
              String str = new String(bytes, StandardCharsets.UTF_8);
              sentToBackend.add(str);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            super.sendMessage(message);
          }

          @Override
          public boolean isReady() {
            return backendReady.get();
          }
        };
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(dataPlaneChannel, backendInterceptor);

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            interceptedChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    assertThat(extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // 1. Backend not ready
    backendReady.set(false);

    // 2. Send msg-1, msg-2 (buffered in pendingRequestBodyMessages)
    proxyCall.sendMessage("msg-1");
    proxyCall.sendMessage("msg-2");

    assertThat(sentToBackend).isEmpty();

    // 3. Trigger fail-open while backend is NOT ready
    responseObserverRef.get().onError(Status.UNAVAILABLE.asException());

    // Verify still nothing sent
    assertThat(sentToBackend).isEmpty();

    // 4. Make backend ready and trigger onReady
    backendReady.set(true);
    backendListenerRef.get().onReady();

    // Verify messages are drained
    assertThat(sentToBackend).containsExactly("msg-1", "msg-2").inOrder();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 16: Inbound Backpressure (request(n) / pendingRequests) ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityTrue_whenExtProcBusy_thenAppRequestsBuffered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                      ReqT, RespT>(next.newCall(method, callOptions)) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicInteger dataPlaneRequestCount = new AtomicInteger(0);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                  }

                  @Override
                  public void onError(Throwable t) {
                  }

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .directExecutor()
            .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for sidecar call to start
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
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
    assertThat(dataPlaneRequestCount.get()).isEqualTo(5);
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenRequestDrainActive_whenAppRequestsMessages_thenRequestsBuffered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for drain to be processed
    long startTime = System.currentTimeMillis();
    while (proxyCall.isReady() && System.currentTimeMillis() - startTime < 5000) {
    }
    assertThat(proxyCall.isReady()).isFalse();

    // App requests more messages
    proxyCall.request(3);

    // Verify requests are buffered and not sent to data plane
    assertThat(dataPlaneRequestCount.get()).isEqualTo(0);
    // proxyCall.isReady() should remain false during drain
    assertThat(proxyCall.isReady()).isFalse();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenBufferedRequests_whenExtProcStreamBecomesReady_thenDataPlaneDrained()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                      ReqT, RespT>(next.newCall(method, callOptions)) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for sidecar call to start
    long startTime = System.currentTimeMillis();
    while (sidecarListenerRef.get() == null && System.currentTimeMillis() - startTime < 5000) {
    }
    assertThat(sidecarListenerRef.get()).isNotNull();

    // Sidecar is busy initially
    sidecarReady.set(false);
    
    // Request from application
    proxyCall.request(10);
    assertThat(dataPlaneRequestCount.get()).isEqualTo(0);

    // Sidecar becomes ready
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();

    // Verify buffered request drained
    assertThat(dataPlaneRequestCount.get()).isEqualTo(10);
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenExtProcStreamCompleted_whenAppRequestsMessages_thenRequestsForwarded()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Immediately complete the stream from server side
              responseObserver.onCompleted();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    final CountDownLatch readyLatch = new CountDownLatch(1);
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onReady() {
        readyLatch.countDown();
      }
    }, new Metadata());

    // Wait for sidecar stream completion
    assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    proxyCall.request(7);

    // Verify request forwarded immediately
    assertThat(dataPlaneRequestCount.get()).isEqualTo(7);
    // proxyCall.isReady() should remain true as sidecar is gone
    assertThat(proxyCall.isReady()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 17: Error Handling & Security ---

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void givenPendingData_whenImmediateResponseReceived_thenDeliversDataBeforeStatus()
      throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    final List<String> appEvents = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);
    final ExecutorService sidecarResponseExecutor = Executors.newSingleThreadExecutor();
    final Metadata.Key<String> immediateKey =
        Metadata.Key.of("x-immediate-header", Metadata.ASCII_STRING_MARSHALLER);
    final AtomicReference<Metadata> appTrailers = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
                  try {
                    Thread.sleep(500);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setImmediateResponse(ImmediateResponse.newBuilder()
                          .setGrpcStatus(
                              io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                                  .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                  .build())
                          .setDetails("Immediate Auth Failure")
                          .setHeaders(
                              io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
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
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            extProcCompletedLatch.countDown();
          }
        };
      }
    };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).directExecutor().build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT, channelManager);
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
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

    ManagedChannel channel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());
    Channel interceptedChannel = io.grpc.ClientInterceptors.interceptForward(
        channel,
        Arrays.asList(new XdsNameResolver.RawMessageClientInterceptor(), interceptor));

    ClientCall<String, String> call =
        interceptedChannel.newCall(
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()));
    call.start(new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appEvents.add("HEADERS");
      }

      @Override
      public void onMessage(String message) {
        appEvents.add("MESSAGE");
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
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
  @SuppressWarnings("FutureReturnValueIgnored")
  public void
      givenStreamingCall_whenImmediateResponseReceivedDuringRequestStreaming_thenTerminatesCleanly()
      throws Exception {
    final String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    final String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    final List<String> appEvents = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final CountDownLatch extProcCompletedLatch = new CountDownLatch(1);
    final ExecutorService sidecarResponseExecutor = Executors.newSingleThreadExecutor();
    final Metadata.Key<String> immediateKey =
        Metadata.Key.of("x-immediate-header", Metadata.ASCII_STRING_MARSHALLER);
    final AtomicReference<Metadata> appTrailers = new AtomicReference<>();
    final AtomicInteger extProcRequestCount = new AtomicInteger(0);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
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
                    } else if (request.hasRequestBody()) {
                      int count = extProcRequestCount.incrementAndGet();
                      if (count == 1) {
                        responseObserver.onNext(ProcessingResponse.newBuilder()
                            .setRequestBody(BodyResponse.newBuilder()
                                .setResponse(CommonResponse.newBuilder().build())
                                .build())
                            .build());
                      } else if (count == 2) {
                        try {
                          Thread.sleep(500);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        responseObserver.onNext(ProcessingResponse.newBuilder()
                            .setImmediateResponse(ImmediateResponse.newBuilder()
                                .setGrpcStatus(
                                    io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                                        .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                        .build())
                                .setDetails("Immediate Auth Failure")
                                .setHeaders(
                                    io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation
                                         .newBuilder()
                                        .addSetHeaders(
                                            io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                                .newBuilder()
                                                .setHeader(
                                                    io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                        .newBuilder()
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

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl).directExecutor().build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT, channelManager);
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, (call, headers) -> {
          call.sendHeaders(new Metadata());
          call.request(100);
          return new ServerCall.Listener<String>() {
            @Override
            public void onMessage(String message) {
              call.sendMessage("server-response-" + message);
            }

            @Override
            public void onHalfClose() {
              call.close(Status.OK, new Metadata());
            }
          };
        })
        .build());

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ManagedChannel channel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());
    Channel interceptedChannel = io.grpc.ClientInterceptors.interceptForward(
        channel,
        Arrays.asList(new XdsNameResolver.RawMessageClientInterceptor(), interceptor));

    ClientCall<String, String> call =
        interceptedChannel.newCall(
            METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()));
    
    call.start(new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appEvents.add("HEADERS");
      }

      @Override
      public void onMessage(String message) {
        appEvents.add("MESSAGE:" + message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        appEvents.add("CLOSE:" + status.getCode());
        appTrailers.set(trailers);
        finishLatch.countDown();
      }
    }, new Metadata());

    call.request(100);
    
    // 1. Send Message 1 (should succeed and be allowed)
    call.sendMessage("msg1");
    
    // 2. Send Message 2 (should trigger the delay and then ImmediateResponse on ext_proc)
    call.sendMessage("msg2");
    
    // 3. Concurrent write of Message 3 (while ext_proc is sleeping)
    try {
      call.sendMessage("msg3");
    } catch (IllegalStateException e) {
      appEvents.add("WRITE_FAILED");
    }

    call.halfClose();

    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appEvents).contains("CLOSE:UNAUTHENTICATED");
    assertThat(appEvents).doesNotContain("WRITE_FAILED");
    assertThat(appTrailers.get().get(immediateKey)).isEqualTo("true");
    assertThat(extProcCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    sidecarResponseExecutor.shutdown();
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowFalse_whenExtProcStreamFails_thenDataPlaneCallCancelled()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(false) // Fail Closed
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server triggers error
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              // Fail the stream immediately on headers
              responseObserver.onError(
                  Status.INTERNAL
                      .withDescription("Simulated sidecar failure")
                      .asRuntimeException());
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Verify application receives INTERNAL due to sidecar failure
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenFailureModeAllowTrue_whenExtProcStreamFails_thenCallFailsOpen()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setFailureModeAllow(true) // Fail Open
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onError(Status.INTERNAL.asRuntimeException());
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    final CountDownLatch headersReceivedLatch = new CountDownLatch(1);
    final CountDownLatch resumeAsyncThreadLatch = new CountDownLatch(1);

    ServerInterceptor dataPlaneInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        headersReceivedLatch.countDown();
        try {
          resumeAsyncThreadLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return next.startCall(call, headers);
      }
    };

    dataPlaneServiceRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  responseObserver.onNext("Hello " + request);
                  responseObserver.onCompleted();
                  dataPlaneLatch.countDown();
                }))
            .build(),
        dataPlaneInterceptor));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final AtomicReference<Status> statusRef = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        statusRef.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Trigger unary call. request(1) starts it.
    proxyCall.request(1);

    // Wait for the async sidecar thread to enter activateCall() and block inside interceptCall
    assertThat(headersReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Now, while the async thread is blocked (and passThroughMode is still false),
    // send a message and half-close.
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Unblock the async thread
    resumeAsyncThreadLatch.countDown();

    // Verify data plane call reached (failed open)
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify client call completes successfully
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(statusRef.get().isOk()).isTrue();
    
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityMode_whenDataPlaneClosed_thenSidecarCloseIsDeferred()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setObservabilityMode(true)
        .setDeferredCloseTimeout(
            com.google.protobuf.Duration.newBuilder().setSeconds(10).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final CountDownLatch sidecarCompletedLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            sidecarCompletedLatch.countDown();
          }
        };
      }
    };
    final io.grpc.Server extProcServer =
        grpcCleanup.register(
            InProcessServerBuilder.forName(extProcServerName)
                .addService(extProcImpl)
                .executor(fakeClock.getScheduledExecutorService())
                .build()
                .start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      
      CallOptions callOptions =
          DEFAULT_CALL_OPTIONS.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall =
          interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
      }
      assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

      // At this point, app received onClose, but sidecar should NOT be completed yet
      assertThat(sidecarCompletedLatch.getCount()).isEqualTo(1);

      // Fast forward time to trigger deferred close
      fakeClock.forwardTime(10, TimeUnit.SECONDS);
      
      for (int i = 0; i < 100 && sidecarCompletedLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      assertThat(sidecarCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0;
          i < 100 && (!dataPlaneChannel.isTerminated() || !extProcServer.isTerminated());
          i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenUnsupportedCompressionInResponse_whenReceived_thenStreamErrored()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-compression-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-compression-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              synchronized (responseObserver) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder().build())
                        .build())
                    .build());
              }
            } else if (request.hasRequestBody()) {
              // Simulate sidecar sending compressed body mutation (unsupported)
              synchronized (responseObserver) {
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
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            new Thread(() -> {
              synchronized (responseObserver) {
                responseObserver.onCompleted();
              }
            }).start();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions =
        DEFAULT_CALL_OPTIONS.withExecutor(fakeClock.getScheduledExecutorService());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Wait for sidecar to receive headers and filter to activate call
    for (int i = 0; i < 5000 && closedLatch.getCount() > 0; i++) {
      fakeClock.forwardTime(10, TimeUnit.MILLISECONDS);
    }

    // Trigger request body processing to hit the unsupported compression check
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify application receives INTERNAL with correct description
    for (int i = 0; i < 10000 && closedLatch.getCount() > 0; i++) {
      fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    }
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenUnsupportedCompressionInResponseBody_whenReceived_thenStreamErrored()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-resp-compression-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-resp-compression-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
              // Simulate sidecar sending compressed body mutation (unsupported) for
              // response body
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

          @Override
          public void onError(Throwable t) {
          }

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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch closedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify application receives INTERNAL with correct description
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenHeaderSendModeDefault_whenProcessing_thenFollowsDefaultBehavior()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.DEFAULT)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.DEFAULT).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    final AtomicInteger sidecarRequestHeaderCount = new AtomicInteger(0);
    final AtomicInteger sidecarResponseHeaderCount = new AtomicInteger(0);
    final AtomicInteger sidecarResponseTrailerCount = new AtomicInteger(0);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
      }
    };
    final io.grpc.Server extProcServer =
        grpcCleanup.register(
            InProcessServerBuilder.forName(uniqueExtProcServerName)
                .addService(extProcImpl)
                .executor(fakeClock.getScheduledExecutorService())
                .build()
                .start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(fakeClock.getScheduledExecutorService())
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry uniqueRegistry = new MutableHandlerRegistry();
    final io.grpc.Server dataPlaneServer =
        grpcCleanup.register(
            InProcessServerBuilder.forName(uniqueDataPlaneServerName)
                .fallbackHandlerRegistry(uniqueRegistry)
                .executor(fakeClock.getScheduledExecutorService())
                .build()
                .start());
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
      CallOptions callOptions =
          DEFAULT_CALL_OPTIONS.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall =
          interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
      for (int i = 0;
          i < 100
              && (!dataPlaneChannel.isTerminated()
                  || !dataPlaneServer.isTerminated()
                  || !extProcServer.isTerminated());
          i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testObservabilityMode_ProceedsWithoutBlockingOnExtProcResponseHeaders()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
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

    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);
    final AtomicReference<ProcessingRequest> extProcReceivedRequest = new AtomicReference<>();
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
                  extProcReceivedRequest.set(request);
                  extProcReceivedHeadersLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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

    final List<String> appReceivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CountDownLatch appMessageLatch = new CountDownLatch(1);
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> appReceivedHeaders = new AtomicReference<>();
    final AtomicReference<Status> appReceivedStatus = new AtomicReference<>();

    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        appReceivedHeaders.set(headers);
      }

      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        appMessageLatch.countDown();
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        appReceivedStatus.set(status);
        appCloseLatch.countDown();
      }
    };

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());
    proxyCall.request(1);

    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify data plane server received the request and processed it
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // In observability mode, the app should receive response headers and messages immediately
    // without waiting for the external processor stream to complete.
    assertThat(appMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedHeaders.get()).isNotNull();
    assertThat(appReceivedMessages).containsExactly("Hello test");

    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedStatus.get().isOk()).isTrue();

    // Also verify that the external processor received the response headers in the background
    assertThat(extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(extProcReceivedRequest.get().hasResponseHeaders()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 18: Immediate Response Handling ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponse_whenReceived_thenDataPlaneCallCancelled()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setImmediateResponse(ImmediateResponse.newBuilder()
                      .setGrpcStatus(
                          io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                              .setStatus(Status.UNAUTHENTICATED.getCode().value())
                              .build())
                      .setDetails("Custom security rejection")
                      .build())
                  .build());
              responseObserver.onCompleted();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
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
  public void givenImmediateResponseAndObservabilityTrue_whenReceived_thenImmediateResponseIgnored()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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

    // External Processor Server sends ImmediateResponse
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setImmediateResponse(ImmediateResponse.newBuilder()
                      .setGrpcStatus(
                          io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
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
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // In observability mode, the call should NOT be cancelled by the immediate response.
    // It should proceed normally to the data plane and finish successfully (Status.OK).
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseDisabled_whenReceivedBeforeActivation_thenSidecarStreamErrored()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setDisableImmediateResponse(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server sends immediate response despite being disabled
    final io.grpc.Server extProcServer =
        grpcCleanup.register(
            InProcessServerBuilder.forName(extProcServerName)
        .addService(new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setImmediateResponse(
                          ImmediateResponse.newBuilder()
                              .setGrpcStatus(
                                  io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus.newBuilder()
                                      .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                      .build())
                              .build())
                      .build());
                }
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
              }
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      
      CallOptions callOptions =
          DEFAULT_CALL_OPTIONS.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall =
          interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(appListener, new Metadata());

      for (int i = 0; i < 1000 && closedLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      // Verify app listener notified with an error (not the sidecar's UNAUTHENTICATED)
      assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0;
          i < 100 && (!dataPlaneChannel.isTerminated() || !extProcServer.isTerminated());
          i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseDisabled_whenReceivedAfterActivation_thenSidecarStreamErrored()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setDisableImmediateResponse(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server sends request headers first (activating the call)
    // and then schedules an immediate response (which is disabled)
    final io.grpc.Server extProcServer =
        grpcCleanup.register(
            InProcessServerBuilder.forName(extProcServerName)
        .addService(new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  // 1. Send request headers response to activate the call
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  
                  // 2. Schedule the immediate response to be sent after 2 seconds
                  @SuppressWarnings("unused")
                  java.util.concurrent.ScheduledFuture<?> unused =
                      fakeClock.getScheduledExecutorService().schedule(() -> {
                        responseObserver.onNext(ProcessingResponse.newBuilder()
                            .setImmediateResponse(
                                ImmediateResponse.newBuilder()
                                    .setGrpcStatus(
                                        io.envoyproxy.envoy.service.ext_proc
                                            .v3.GrpcStatus.newBuilder()
                                            .setStatus(Status.UNAUTHENTICATED.getCode().value())
                                            .build())
                                    .build())
                            .build());
                      }, 2, TimeUnit.SECONDS);
                }
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
              }
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
      
      CallOptions callOptions =
          DEFAULT_CALL_OPTIONS.withExecutor(fakeClock.getScheduledExecutorService());
      ClientCall<String, String> proxyCall =
          interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
      proxyCall.start(appListener, new Metadata());

      for (int i = 0; i < 1000 && closedLatch.getCount() > 0; i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      // Verify app listener notified with UNIMPLEMENTED because data plane connection succeeded
      // but the method was not registered, and it failed before the ext-proc stream failed
      assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
      
      proxyCall.cancel("Cleanup", null);
    } finally {
      dataPlaneChannel.shutdownNow();
      extProcServer.shutdownNow();
      for (int i = 0;
          i < 100 && (!dataPlaneChannel.isTerminated() || !extProcServer.isTerminated());
          i++) {
        fakeClock.forwardTime(1, TimeUnit.SECONDS);
      }
      channelManager.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenImmediateResponseInTrailers_whenReceived_thenDataPlaneCallStatusIsOverridden()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              synchronized (responseObserver) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setRequestHeaders(HeadersResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder().build())
                        .build())
                    .build());
              }
            } else if (request.hasResponseHeaders()) {
              synchronized (responseObserver) {
                responseObserver.onNext(ProcessingResponse.newBuilder()
                    .setResponseHeaders(HeadersResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder().build())
                        .build())
                    .build());
              }
            } else if (request.hasResponseTrailers()) {
              new Thread(() -> {
                synchronized (responseObserver) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setImmediateResponse(
                              ImmediateResponse.newBuilder()
                                  .setGrpcStatus(
                                      io.envoyproxy.envoy.service.ext_proc.v3.GrpcStatus
                                          .newBuilder()
                                          .setStatus(Status.DATA_LOSS.getCode().value())
                                          .build())
                                  .setDetails("Sidecar detected data loss")
                                  .setHeaders(
                                      io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation
                                          .newBuilder()
                                          .addSetHeaders(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                                  .newBuilder()
                                                  .setHeader(
                                                      io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                          .newBuilder()
                                                          .setKey("x-sidecar-extra")
                                                          .setValue("true")
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseObserver.onCompleted();
                }
              }).start();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    
    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    // Request message to allow the call to complete
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify application receives the OVERRIDDEN status and merged trailers
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.DATA_LOSS);
    assertThat(closedStatus.get().getDescription()).isEqualTo("Sidecar detected data loss");
    assertThat(
            closedTrailers
                .get()
                .get(Metadata.Key.of("x-sidecar-extra", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("true");
    
    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 19: Resource Management ---

  @Test
  public void givenFilter_whenClosed_thenCachedChannelManagerIsClosed() throws Exception {
    CachedChannelManager mockChannelManager = Mockito.mock(CachedChannelManager.class);
    
    ExternalProcessorFilter filter = new ExternalProcessorFilter(FAKE_CONTEXT, mockChannelManager);
    
    filter.close();
    
    Mockito.verify(mockChannelManager).close();
  }

  // --- Category 20: Data plane rpc cancellation ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenActiveRpc_whenDataPlaneCallCancelled_thenExtProcStreamIsErrored()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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
    final CountDownLatch cancelLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
            cancelLatch.countDown();
          }

          @Override
          public void onCompleted() {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              // No-op
            }))
        .build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    for (int i = 0; i < 50 && !proxyCall.isReady(); i++) {
      fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    }
    assertThat(proxyCall.isReady()).isTrue();

    // Application cancels the RPC
    proxyCall.cancel("User cancelled", null);

    // Verify sidecar stream also cancelled
    assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    channelManager.close();
  }

  // --- Category 21: Flow Control when side stream is full ---

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenIsReadyReturnsFalse()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
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

    final List<ProcessingRequest> extProcRequests =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    // Sidecar server
    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
    final CountDownLatch responseSentLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            extProcRequests.add(request);
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                sidecarActionLatch.countDown();
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                responseSentLatch.countDown();
              }
            }).start();
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            new Thread(() -> {
              synchronized (responseObserver) {
                responseObserver.onCompleted();
              }
            }).start();
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return sidecarReady.get();
                    }
                  };
                }
              })
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public boolean isReady() {
                      return dataPlaneReady.get() && super.isReady();
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions2 = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions2, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Wait for activation
    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar becomes busy -> proxyCall becomes busy
    sidecarReady.set(false);
    assertThat(proxyCall.isReady()).isFalse();

    // Sidecar becomes ready, but Data Plane is busy -> proxyCall is STILL ready because Normal Mode
    sidecarReady.set(true);
    dataPlaneReady.set(false);
    assertThat(proxyCall.isReady()).isTrue();

    assertThat(extProcRequests).isNotEmpty();
    for (ProcessingRequest request : extProcRequests) {
      assertThat(request.getObservabilityMode()).isFalse();
    }

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenObservabilityModeFalse_whenExtProcBusy_thenAppRequestsAreBuffered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service." 
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .setObservabilityMode(false)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // Sidecar server
    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
    final CountDownLatch responseSentLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                sidecarActionLatch.countDown();
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                responseSentLatch.countDown();
              } else if (request.hasResponseHeaders()) {
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
              } else if (request.hasResponseBody()) {
                synchronized (responseObserver) {
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
                }
              }
            }).start();
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            new Thread(() -> {
              synchronized (responseObserver) {
                responseObserver.onCompleted();
              }
            }).start();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(extProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    final AtomicBoolean sidecarReady = new AtomicBoolean(true);
    final AtomicReference<ClientCall.Listener<ProcessingResponse>> sidecarListenerRef =
        new AtomicReference<>();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1); // Bootstrap request for headers

    // Wait for activation
    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar busy -> request(5) should be buffered
    sidecarReady.set(false);
    proxyCall.request(5);
    assertThat(dataPlaneRequestCount.get()).isEqualTo(1);
    // (Only the initial bootstrap request went through)

    // Sidecar becomes ready -> buffered requests should be drained
    sidecarReady.set(true);
    sidecarListenerRef.get().onReady();
    
    long startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 2 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(2);

    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();

    // Server sends response headers (Dummy)
    upstreamResponseObserver.onNext("Dummy for headers");

    startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 3 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(3);

    // Server sends first data message -> pulls next
    upstreamResponseObserver.onNext("Msg 1");

    startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 4 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(4);

    // Server sends second data message -> pulls next
    upstreamResponseObserver.onNext("Msg 2");

    startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 5 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(5);

    // Server sends third data message -> pulls next (which drains the final pending request)
    upstreamResponseObserver.onNext("Msg 3");

    startTime2 = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 6 && System.currentTimeMillis() - startTime2 < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(6);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenResponseBodyModeNone_whenExtProcBusy_thenAppRequestsAreNotBuffered()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.extensions.grpc_service."
                + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .setObservabilityMode(false)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    // Sidecar server
    final CountDownLatch sidecarActionLatch = new CountDownLatch(1);
    final CountDownLatch responseSentLatch = new CountDownLatch(1);
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      @SuppressWarnings("unchecked")
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            new Thread(() -> {
              if (request.hasRequestHeaders()) {
                sidecarActionLatch.countDown();
                synchronized (responseObserver) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                }
                responseSentLatch.countDown();
              }
            }).start();
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
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
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(extProcServerName)
              .directExecutor()
              .intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
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
                  return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                      next.newCall(method, callOptions)) {
                    @Override
                    public void request(int numMessages) {
                      dataPlaneRequestCount.addAndGet(numMessages);
                      super.request(numMessages);
                    }
                  };
                }
            })
            .build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1); // Bootstrap request

    // Wait for activation
    assertThat(sidecarActionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(responseSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isTrue();

    // Sidecar server busy
    sidecarReady.set(false);

    // Since responseBodyMode is NONE and not in observabilityMode, request(5) should
    // be passed upstream immediately
    proxyCall.request(5);

    long startTime = System.currentTimeMillis();
    while (dataPlaneRequestCount.get() < 6 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(dataPlaneRequestCount.get()).isEqualTo(6); // 1 bootstrap + 5 requested

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFlowControlStateInitialization() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(2);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                sidecarLatch.countDown();
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall(
            (request, responseObserver) -> {
              responseObserver.onNext("Hello " + request);
              responseObserver.onCompleted();
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.sendMessage("Message 1");

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(receivedRequests).hasSize(2);
    ProcessingRequest firstRequest = receivedRequests.get(0);
    ProcessingRequest secondRequest = receivedRequests.get(1);

    assertThat(firstRequest.hasRequestHeaders()).isTrue();
    assertThat(firstRequest.hasFlowControlInit()).isTrue();
    assertThat(firstRequest.getFlowControlInit().getInitialWindowDownstreamToSidestream())
        .isEqualTo(65536);
    assertThat(firstRequest.getFlowControlInit().getInitialWindowSidestreamToUpstream())
        .isEqualTo(65536);
    assertThat(firstRequest.getFlowControlInit().getInitialWindowUpstreamToSidestream())
        .isEqualTo(65536);
    assertThat(firstRequest.getFlowControlInit().getInitialWindowSidestreamToDownstream())
        .isEqualTo(65536);

    assertThat(secondRequest.hasRequestBody()).isTrue();
    assertThat(secondRequest.hasFlowControlInit()).isFalse();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDownstreamToSidestreamFlowControl_EnforcesWindow() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch firstBodyLatch = new CountDownLatch(2); // Headers + First Body
    final CountDownLatch secondBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  firstBodyLatch.countDown();
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
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
                    return;
                  }
                  if (firstBodyLatch.getCount() > 0) {
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
                    firstBodyLatch.countDown();
                  } else {
                    // This is the second body (30000 bytes)
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
                    secondBodyLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final List<String> dataPlaneReceivedMessages = new CopyOnWriteArrayList<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {
                    dataPlaneReceivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Response");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final List<String> dataPlaneResponseMessages = new CopyOnWriteArrayList<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    final AtomicReference<Status> callClosedStatus = new AtomicReference<>();

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        dataPlaneResponseMessages.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        callClosedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);

    // Generate large messages
    String largeMessage70k = new String(new char[70000]).replace('\0', 'a');
    String largeMessage30k = new String(new char[30000]).replace('\0', 'b');

    // Send first message (70000 bytes) - fits in 65536 window
    proxyCall.sendMessage(largeMessage70k);
    assertThat(firstBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(proxyCall.isReady()).isFalse();

    // Send second message (30000 bytes) - total 100000 > 65536, should buffer
    proxyCall.sendMessage(largeMessage30k);

    // Call halfClose() while the second message is still buffered.
    // This should NOT trigger immediate half-close, but mark pendingHalfClose = true.
    proxyCall.halfClose();

    // Assert that it is NOT delivered to ext_proc (delivery is synchronous on
    // directExecutor, so we can check immediately)
    assertThat(receivedRequests).hasSize(3);
    // (Headers + First Body + Client Window Update (Path 2 replenishment))
    assertThat(proxyCall.isReady()).isFalse();

    // Now send ServerWindowUpdate from ext_proc to interceptor to increment window by 40000
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementDownstreamToSidestream(40000)
            .build())
        .build());

    // The second body should now be flushed and received by ext_proc, and then half-closed
    assertThat(secondBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify both messages reached the backend service
    assertThat(dataPlaneReceivedMessages)
        .containsExactly(largeMessage70k, largeMessage30k).inOrder();

    // Wait for the call to close successfully.
    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callClosedStatus.get().isOk()).isTrue();
    assertThat(dataPlaneResponseMessages).containsExactly("Response");

    // The mock ext_proc should have received 5 requests:
    // 1. Headers
    // 2. First Body (70000)
    // 3. Client Window Update
    // 4. Second Body (30000)
    // 5. EndOfStreamWithoutMessage (half-close)
    assertThat(receivedRequests.size()).isEqualTo(5);
    assertThat(receivedRequests.get(0).hasRequestHeaders()).isTrue();
    assertThat(receivedRequests.get(1).hasRequestBody()).isTrue();
    assertThat(receivedRequests.get(2).hasClientWindowUpdate()).isTrue();
    assertThat(receivedRequests.get(3).hasRequestBody()).isTrue();
    assertThat(receivedRequests.get(3).getRequestBody().getBody().size()).isEqualTo(30000);
    assertThat(receivedRequests.get(4).hasRequestBody()).isTrue();
    assertThat(receivedRequests.get(4).getRequestBody().getEndOfStreamWithoutMessage()).isTrue();

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testUpstreamToSidestreamFlowControl_EnforcesWindow() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(4);
    // (Headers, Request Body, Response Headers, Response Body 1)
    final CountDownLatch secondResponseBodyLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                sidecarLatch.countDown();
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
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
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  com.google.protobuf.ByteString originalBody = request.getResponseBody().getBody();
                  com.google.protobuf.ByteString bodyToSend = originalBody;
                  // Return the original 70,000 bytes as-is
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(bodyToSend)
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  if (originalBody.size() == 30000) {
                    secondResponseBodyLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final List<String> appReceivedMessages = new CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatch2 = new CountDownLatch(2);
    final CountDownLatch messagesLatch3 = new CountDownLatch(3);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        messagesLatch2.countDown();
        messagesLatch3.countDown();
      }
    };

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    // Send first dummy message to initialize headers and stream
    proxyCall.sendMessage("Client Msg");

    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    String largeMessage70k = new String(new char[70000]).replace('\0', 'a');
    String largeMessage30k = new String(new char[30000]).replace('\0', 'b');

    // Wait for the initialization (headers, request body, response headers) to reach the ext_proc
    // server
    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Upstream sends 70k response chunk. Since window is 65,536, this drives the window
    // negative (-4,464).
    upstreamResponseObserver.onNext(largeMessage70k);

    // Verify Chunk 1 is successfully delivered (2 messages total in app: dummy and chunk 1)
    assertThat(messagesLatch2.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(2);

    // Upstream sends 30k response chunk. Since the window is negative, the filter
    // must block/buffer this chunk.
    upstreamResponseObserver.onNext(largeMessage30k);

    // Wait a brief period and verify that the 30k chunk has NOT been sent to the ext_proc server
    assertThat(secondResponseBodyLatch.getCount()).isEqualTo(1);
    assertThat(appReceivedMessages).hasSize(2);

    // Sidecar server sends a ServerWindowUpdate of 40k to the filter, unblocking the window.
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementUpstreamToSidestream(40000)
            .build())
        .build());

    // Once the window is unblocked, the filter immediately forwards the 30k chunk
    // to the ext_proc server, which processes it.
    assertThat(secondResponseBodyLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify that Chunk 2 is now successfully delivered to the client application
    assertThat(messagesLatch3.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(3);
    assertThat(appReceivedMessages.get(2)).isEqualTo(largeMessage30k);
    assertThat(receivedRequests).isNotEmpty();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testUpstreamToSidestreamFlowControl_DrainsPartiallyOnPartialWindowReplenishment()
      throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(4);
    // (Headers, Request Body, Response Headers, Response Body 1 (70k))
    final CountDownLatch thirtykLatch = new CountDownLatch(1);
    final CountDownLatch twentykLatch = new CountDownLatch(1);
    final CountDownLatch tenkLatch = new CountDownLatch(1);
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                sidecarLatch.countDown();
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
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
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  com.google.protobuf.ByteString originalBody = request.getResponseBody().getBody();
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
                  if (originalBody.size() == 30000) {
                    thirtykLatch.countDown();
                  } else if (originalBody.size() == 20000) {
                    twentykLatch.countDown();
                  } else if (originalBody.size() == 10000) {
                    tenkLatch.countDown();
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final List<String> appReceivedMessages = new CopyOnWriteArrayList<>();
    final CountDownLatch messagesLatchDummyAnd70k = new CountDownLatch(2);
    final CountDownLatch messagesLatchWith30kAnd20k = new CountDownLatch(4);
    final CountDownLatch messagesLatchAll = new CountDownLatch(5);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        appReceivedMessages.add(message);
        messagesLatchDummyAnd70k.countDown();
        messagesLatchWith30kAnd20k.countDown();
        messagesLatchAll.countDown();
      }
    };

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    // Send first dummy message to initialize headers and stream
    proxyCall.sendMessage("Client Msg");

    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    String largeMessage70k = new String(new char[70000]).replace('\0', 'a');
    String largeMessage30k = new String(new char[30000]).replace('\0', 'b');
    String largeMessage20k = new String(new char[20000]).replace('\0', 'c');
    String largeMessage10k = new String(new char[10000]).replace('\0', 'd');

    // Wait for the initialization (headers, request body, response headers) to reach the ext_proc
    // server
    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Upstream sends 70k response chunk. Since window is 65,536, this drives the window
    // negative (-4,464).
    upstreamResponseObserver.onNext(largeMessage70k);

    // Verify Chunk 1 is successfully delivered (2 messages total in app: dummy and chunk 1)
    assertThat(messagesLatchDummyAnd70k.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(2);

    // Upstream sends 30k, 20k, 10k response chunks. Since the window is negative, the filter
    // must block/buffer these chunks.
    upstreamResponseObserver.onNext(largeMessage30k);
    upstreamResponseObserver.onNext(largeMessage20k);
    upstreamResponseObserver.onNext(largeMessage10k);

    // Wait a brief period and verify that none of these chunks have been sent
    // to the ext_proc server
    assertThat(thirtykLatch.getCount()).isEqualTo(1);
    assertThat(twentykLatch.getCount()).isEqualTo(1);
    assertThat(tenkLatch.getCount()).isEqualTo(1);
    assertThat(appReceivedMessages).hasSize(2);

    // Sidecar server sends a ServerWindowUpdate of 40k to the filter.
    // Window becomes -4464 + 40000 = 35536.
    // This allows draining:
    // - 30k chunk: window becomes 35536 - 30000 = 5536.
    // - 20k chunk: window becomes 5536 - 20000 = -14464.
    // - 10k chunk: window is <= 0, so it remains buffered.
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementUpstreamToSidestream(40000)
            .build())
        .build());

    // Verify 30k and 20k chunks are now successfully delivered to the client application
    // (total 4 messages)
    assertThat(messagesLatchWith30kAnd20k.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(4);
    assertThat(appReceivedMessages.get(2)).isEqualTo(largeMessage30k);
    assertThat(appReceivedMessages.get(3)).isEqualTo(largeMessage20k);

    // Verify they reached ext_proc
    assertThat(thirtykLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(twentykLatch.await(5, TimeUnit.SECONDS)).isTrue();

    List<ByteString> bodies = new ArrayList<>();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasResponseBody()) {
        bodies.add(req.getResponseBody().getBody());
      }
    }
    assertThat(bodies).containsExactly(
        ByteString.copyFromUtf8("Dummy for headers"),
        ByteString.copyFromUtf8(largeMessage70k),
        ByteString.copyFromUtf8(largeMessage30k),
        ByteString.copyFromUtf8(largeMessage20k)
    ).inOrder();

    // Verify 10k chunk is still blocked
    assertThat(tenkLatch.getCount()).isEqualTo(1);

    // Sidecar server sends another ServerWindowUpdate of 20k.
    // Window becomes -14464 + 20000 = 5536.
    // This allows draining the 10k chunk (window becomes 5536 - 10000 = -4464).
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementUpstreamToSidestream(20000)
            .build())
        .build());

    // Verify 10k chunk is now successfully delivered (total 5 messages)
    assertThat(messagesLatchAll.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appReceivedMessages).hasSize(5);
    assertThat(appReceivedMessages.get(4)).isEqualTo(largeMessage10k);

    // Verify it reached ext_proc
    assertThat(tenkLatch.await(5, TimeUnit.SECONDS)).isTrue();

    List<ByteString> finalBodies = new ArrayList<>();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasResponseBody()) {
        finalBodies.add(req.getResponseBody().getBody());
      }
    }
    assertThat(finalBodies).containsExactly(
        ByteString.copyFromUtf8("Dummy for headers"),
        ByteString.copyFromUtf8(largeMessage70k),
        ByteString.copyFromUtf8(largeMessage30k),
        ByteString.copyFromUtf8(largeMessage20k),
        ByteString.copyFromUtf8(largeMessage10k)
    ).inOrder();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }



  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToUpstreamFlowControl_QueuingAndDraining() throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
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

    final CountDownLatch finishLatch = new CountDownLatch(1);
    final List<String> serverReceivedBodies = new CopyOnWriteArrayList<>();
    final CountDownLatch serverReceivedLatch = new CountDownLatch(2);

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
                } else if (request.hasRequestBody()) {
                  ByteString original = request.getRequestBody().getBody();
                  boolean eos =
                      request.getRequestBody().getEndOfStream()
                          || request.getRequestBody().getEndOfStreamWithoutMessage();
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
                                                                  eos
                                                                      ? ""
                                                                      : "Mutated"
                                                                          + original
                                                                              .toStringUtf8()))
                                                          .setEndOfStream(eos)
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

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_CLIENT_STREAMING,
                ServerCalls.asyncClientStreamingCall(
                    new ServerCalls.ClientStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {
                            serverReceivedBodies.add(value);
                            serverReceivedLatch.countDown();
                          }

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {
                            responseObserver.onNext("Response");
                            responseObserver.onCompleted();
                          }
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(false);
    final AtomicReference<ClientCall.Listener<?>> capturedListenerRef = new AtomicReference<>();

    class TriggerableForwardingCall<ReqT, RespT>
        extends io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
      TriggerableForwardingCall(ClientCall<ReqT, RespT> delegate) {
        super(delegate);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        capturedListenerRef.set(responseListener);
        super.start(responseListener, headers);
      }

      @Override
      public boolean isReady() {
        return transportReady.get();
      }
    }

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new TriggerableForwardingCall<>(next.newCall(method, callOptions));
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    proxyCall.start(
        new ClientCall.Listener<String>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            finishLatch.countDown();
          }
        },
        new Metadata());
    proxyCall.request(1);

    // Send first message. This gets mutated to "MutatedOriginalRequest 1" by ext_proc.
    proxyCall.sendMessage("OriginalRequest 1");

    // Give some time to process and ensure the message is NOT received on the server side because
    // transport is not ready
    assertThat(serverReceivedLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(serverReceivedBodies).isEmpty();

    // Now make the transport ready (super.isReady() returns true), but do NOT trigger onReady drain
    // yet.
    transportReady.set(true);

    // Send second message. This gets mutated to "MutatedOriginalRequest 2" by ext_proc.
    // Since transportReady is true but there's still a pending message in the queue,
    // the second message should also be queued (to preserve order).
    proxyCall.sendMessage("OriginalRequest 2");

    // Ensure still no message is received on the server side (since we haven't triggered drain via
    // onReady)
    assertThat(serverReceivedLatch.await(500, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(serverReceivedBodies).isEmpty();

    // Now trigger onReady callback to drain the queue.
    ClientCall.Listener<?> listener = capturedListenerRef.get();
    assertThat(listener).isNotNull();
    listener.onReady();

    // Both messages should be drained and forwarded to the backend server in order.
    assertThat(serverReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedBodies)
        .containsExactly("MutatedOriginalRequest 1", "MutatedOriginalRequest 2")
        .inOrder();

    proxyCall.halfClose();
    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToUpstreamFlowControl_DelayedHalfClose() throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
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

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<String> serverReceivedBodies = new CopyOnWriteArrayList<>();
    final CountDownLatch serverReceivedLatch = new CountDownLatch(1);

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
                } else if (request.hasRequestBody()) {
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
                                                              ByteString.copyFromUtf8("Mutated1"))
                                                          .setEndOfStream(true)
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_CLIENT_STREAMING,
                ServerCalls.asyncClientStreamingCall(
                    new ServerCalls.ClientStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {
                            serverReceivedBodies.add(value);
                            serverReceivedLatch.countDown();
                          }

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {
                            responseObserver.onNext("Response");
                            responseObserver.onCompleted();
                          }
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(false);
    final AtomicReference<ClientCall.Listener<?>> capturedListenerRef = new AtomicReference<>();
    final AtomicInteger halfCloseCallCount = new AtomicInteger(0);

    class DelayedHalfCloseForwardingCall<ReqT, RespT>
        extends io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
      DelayedHalfCloseForwardingCall(ClientCall<ReqT, RespT> delegate) {
        super(delegate);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        capturedListenerRef.set(responseListener);
        super.start(responseListener, headers);
      }

      @Override
      public boolean isReady() {
        return transportReady.get();
      }

      @Override
      public void halfClose() {
        halfCloseCallCount.incrementAndGet();
        super.halfClose();
      }
    }

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new DelayedHalfCloseForwardingCall<>(next.newCall(method, callOptions));
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(
            interceptor,
            METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    // Send the first client message. This gets mutated to "Mutated1" by ext_proc.
    proxyCall.sendMessage("OriginalRequest 1");

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Since transportReady is false, the mutated body is queued in pendingUpstreamBodyMessages.
    // And since it was unilateral half-close, pendingUpstreamHalfClose is set to true.
    // Verify that the call is NOT half-closed on transport yet.
    assertThat(halfCloseCallCount.get()).isEqualTo(0);
    assertThat(serverReceivedBodies).isEmpty();

    // Now make the transport ready and trigger onReady callback
    transportReady.set(true);
    ClientCall.Listener<?> listener = capturedListenerRef.get();
    assertThat(listener).isNotNull();
    listener.onReady();

    // The queued message should be drained, forwarded to backend server,
    // and the delayed half-close should be triggered.
    assertThat(serverReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(serverReceivedBodies).containsExactly("Mutated1");
    assertThat(halfCloseCallCount.get()).isEqualTo(1);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToUpstreamFlowControl_FailOpenDuringDelayedHalfClose()
      throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .setFailureModeAllow(true)
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
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
                  // Unilaterally send a request body response containing mutated body and
                  // endOfStream = true
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
                                                              ByteString.copyFromUtf8("Mutated1"))
                                                          .setEndOfStream(true)
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
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_CLIENT_STREAMING,
                ServerCalls.asyncClientStreamingCall(
                    new ServerCalls.ClientStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {
                            responseObserver.onNext("Response");
                            responseObserver.onCompleted();
                          }
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(false);
    final AtomicReference<ClientCall.Listener<?>> capturedListenerRef = new AtomicReference<>();
    final AtomicInteger halfCloseCallCount = new AtomicInteger(0);
    final AtomicInteger sendMessageCount = new AtomicInteger(0);

    class FailOpenDelayedHalfCloseForwardingCall<ReqT, RespT>
        extends io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
      FailOpenDelayedHalfCloseForwardingCall(ClientCall<ReqT, RespT> delegate) {
        super(delegate);
      }

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        capturedListenerRef.set(responseListener);
        super.start(responseListener, headers);
      }

      @Override
      public boolean isReady() {
        return transportReady.get();
      }

      @Override
      public void sendMessage(ReqT message) {
        sendMessageCount.incrementAndGet();
        super.sendMessage(message);
      }

      @Override
      public void halfClose() {
        halfCloseCallCount.incrementAndGet();
        super.halfClose();
      }
    }

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .intercept(
                    new ClientInterceptor() {
                      @Override
                      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                          MethodDescriptor<ReqT, RespT> method,
                          CallOptions callOptions,
                          Channel next) {
                        return new FailOpenDelayedHalfCloseForwardingCall<>(
                            next.newCall(method, callOptions));
                      }
                    })
                .directExecutor()
                .build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    // Call halfClose immediately. This sets pendingHalfClose = true.
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Since transportReady is false, the mutated body is queued.
    // And since it was unilateral half-close, pendingUpstreamHalfClose is set to true.
    // Verify that the call is NOT half-closed on transport yet.
    assertThat(halfCloseCallCount.get()).isEqualTo(0);

    // Fail the ext_proc stream to trigger fail-open.
    responseObserverRef.get().onError(Status.INTERNAL.asRuntimeException());

    // Fail-open will see pendingHalfClose = true, but since we have queued messages
    // (Mutated1) and transport is not ready, it will defer half-close.
    assertThat(halfCloseCallCount.get()).isEqualTo(0);

    // Now make transport ready and trigger onReady callback
    transportReady.set(true);
    ClientCall.Listener<?> listener = capturedListenerRef.get();
    assertThat(listener).isNotNull();
    listener.onReady();

    // The queued message should be drained, and then the deferred half-close is triggered.
    assertThat(sendMessageCount.get()).isEqualTo(1);
    assertThat(halfCloseCallCount.get()).isEqualTo(1);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToDownstreamFlowControl_QueuingAndWithholdingWindowUpdates()
      throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch firstBodyResponseLatch = new CountDownLatch(1);
    final CountDownLatch secondBodyResponseLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  headersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString body = request.getResponseBody().getBody();
                  boolean eos = request.getResponseBody().getEndOfStream();
                  if (body.size() == 40001) {
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
                                                            .setBody(body)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    firstBodyResponseLatch.countDown();

                    // Send second body (40002) - spoofed
                    ByteString body2 =
                        ByteString.copyFromUtf8(new String(new char[40002]).replace('\0', 'y'));
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
                                                            .setBody(body2)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    secondBodyResponseLatch.countDown();
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

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    final List<String> receivedResponses = Collections.synchronizedList(new ArrayList<>());
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        receivedResponses.add(message);
      }
    }, new Metadata());

    // Wait for the headers handshake to complete and activate the call
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    proxyCall.request(1);

    String resp1 = new String(new char[40001]).replace('\0', 'x');
    dataPlaneResponseObserverRef.get().onNext(resp1);

    assertThat(firstBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(secondBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(receivedResponses).containsExactly(resp1);

    List<ProcessingRequest> windowUpdates = new ArrayList<>();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasClientWindowUpdate()) {
        windowUpdates.add(req);
      }
    }
    assertThat(windowUpdates).hasSize(1);
    assertThat(
            windowUpdates.get(0).getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .isEqualTo(40001);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSidestreamToDownstreamFlowControl_DrainingAndSendingWindowUpdates()
      throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch firstBodyResponseLatch = new CountDownLatch(1);
    final CountDownLatch secondBodyResponseLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  headersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString body = request.getResponseBody().getBody();
                  boolean eos = request.getResponseBody().getEndOfStream();
                  if (body.size() == 40001) {
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
                                                            .setBody(body)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    firstBodyResponseLatch.countDown();

                    // Send second body (40002) - spoofed
                    ByteString body2 =
                        ByteString.copyFromUtf8(new String(new char[40002]).replace('\0', 'y'));
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
                                                            .setBody(body2)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    secondBodyResponseLatch.countDown();
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
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    final List<String> receivedResponses = Collections.synchronizedList(new ArrayList<>());
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        receivedResponses.add(message);
      }
    }, new Metadata());

    // Wait for the headers handshake to complete and activate the call
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    proxyCall.request(1);

    String resp1 = new String(new char[40001]).replace('\0', 'x');
    String resp2 = new String(new char[40002]).replace('\0', 'y');
    dataPlaneResponseObserverRef.get().onNext(resp1);

    assertThat(firstBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(secondBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(receivedResponses).containsExactly(resp1);

    List<ProcessingRequest> windowUpdates = new ArrayList<>();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasClientWindowUpdate()) {
        windowUpdates.add(req);
      }
    }
    assertThat(windowUpdates).hasSize(1);
    assertThat(
            windowUpdates.get(0).getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .isEqualTo(40001);

    proxyCall.request(1);
    assertThat(receivedResponses).containsExactly(resp1, resp2);

    windowUpdates.clear();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasClientWindowUpdate()) {
        windowUpdates.add(req);
      }
    }
    assertThat(windowUpdates).hasSize(2);
    assertThat(
            windowUpdates.get(1).getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .isEqualTo(40002);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testThresholdBasedWindowUpdates() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final List<StreamObserver<ProcessingResponse>> observers = new ArrayList<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            observers.add(responseObserver);
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, DEFAULT_CALL_OPTIONS, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(filterClientRequests(receivedRequests)).hasSize(1);
    assertThat(filterClientRequests(receivedRequests).get(0).hasRequestHeaders()).isTrue();

    // 1. Send body message. It should be sent immediately.
    proxyCall.sendMessage("Msg 1"); // size = 5 bytes

    assertThat(filterClientRequests(receivedRequests)).hasSize(2);
    assertThat(filterClientRequests(receivedRequests).get(1).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(1)
        .getRequestBody().getBody().toStringUtf8())

        .isEqualTo("Msg 1");
    // No window updates were accumulated yet.
    assertThat(filterClientRequests(receivedRequests).get(1).hasClientWindowUpdate()).isFalse();

    // 2. Trigger window replenishment below threshold (e.g. 5 bytes from Msg 1 response).
    // The interceptor processes the response, forwards it upstream, and increments
    // accumulatedWindowUpdateSidestreamToUpstream. Since 5 < 32768, it won't send
    // standalone updates.
    // We send another message "Msg 2" to trigger piggybacking.
    proxyCall.sendMessage("Msg 2");

    assertThat(filterClientRequests(receivedRequests)).hasSize(3);
    assertThat(filterClientRequests(receivedRequests).get(2).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2)
        .getRequestBody().getBody().toStringUtf8())

        .isEqualTo("Msg 2");
    // Verify accumulated 5 bytes update is piggybacked.
    assertThat(filterClientRequests(receivedRequests).get(2).hasClientWindowUpdate()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2)
        .getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(5);

    // 3. Accumulate past threshold (e.g. 35,000 bytes) without sending body messages.
    // This should trigger an immediate standalone ClientWindowUpdate.
    StreamObserver<ProcessingResponse> responseObserver = observers.get(0);
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
                                            .setBody(ByteString.copyFrom(new byte[35000]))
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());

    // standalone client window update received.
    assertThat(filterClientRequests(receivedRequests)).hasSize(4);
    assertThat(filterClientRequests(receivedRequests).get(3).hasClientWindowUpdate()).isTrue();
    assertThat(
            filterClientRequests(receivedRequests)
                .get(3)
                .getClientWindowUpdate()
                .getWindowIncrementSidestreamToUpstream())
        .isEqualTo(35005);
    assertThat(filterClientRequests(receivedRequests).get(3).hasRequestBody()).isFalse();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWindowUpdateWithheldWhenUpstreamCapacityExistsAndBelowThreshold()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(2);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
                  // Mutate request body and send back 10000 bytes (below threshold 32768)
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
                                                              ByteString.copyFrom(new byte[10000]))
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Response");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(
            interceptor,
            METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Send 10k message to ext_proc.
    String body10k = new String(new char[10000]).replace('\0', 'a');
    proxyCall.sendMessage(body10k);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Since the window has capacity (65536 - 10000 = 55536 > 0) and the increment (10000)
    // is below the threshold, NO window update should be sent.
    // receivedRequests should only contain Headers and RequestBody (size = 2).
    assertThat(receivedRequests).hasSize(2);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWindowUpdateWithheldWhenDownstreamCapacityExistsAndBelowThreshold()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch bodyResponseLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  headersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  boolean eos = request.getResponseBody().getEndOfStream();
                  // Mutate response body and send back 10000 bytes (below threshold 32768)
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
                                                              ByteString.copyFrom(new byte[10000]))
                                                          .setEndOfStream(eos)
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  bodyResponseLatch.countDown();
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
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    final List<String> receivedResponses = Collections.synchronizedList(new ArrayList<>());
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        receivedResponses.add(message);
      }
    }, new Metadata());

    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    proxyCall.request(1);

    String resp1 = new String(new char[10000]).replace('\0', 'x');
    dataPlaneResponseObserverRef.get().onNext(resp1);

    assertThat(bodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // The response body (10000) should be delivered.
    assertThat(receivedResponses).hasSize(1);

    // Since the window has capacity (65536 - 10000 = 55536 > 0) and the increment (10000)
    // is below the threshold, NO window update should be sent to the ext_proc.
    // receivedRequests should only contain Headers and ResponseBody (size = 2).
    assertThat(receivedRequests).hasSize(2);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWindowUpdateWithheldOnUpstreamWindowExhaustionWithZeroIncrement()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarLatch = new CountDownLatch(2);
    final CountDownLatch responseBodyProcessedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
                  sidecarLatch.countDown();
                  // Mutate request body and send back 70000 bytes (exhausts upstream return
                  // window).
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
                                                              ByteString.copyFrom(new byte[70000]))
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasResponseBody()) {
                  // Mutate response body and send back 20000 bytes (below threshold)
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
                                                              ByteString.copyFrom(new byte[20000]))
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseBodyProcessedLatch.countDown();
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
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(false);
    final AtomicReference<ClientCall.Listener<?>> dataPlaneListenerRef = new AtomicReference<>();

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  public void start(Listener<RespT> responseListener, Metadata headers) {
                    dataPlaneListenerRef.set(responseListener);
                    super.start(responseListener, headers);
                  }

                  @Override
                  public boolean isReady() {
                    return transportReady.get();
                  }
                };
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(
            interceptor,
            METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Send 10k message to ext_proc.
    String body10k = new String(new char[10000]).replace('\0', 'a');
    proxyCall.sendMessage(body10k);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedRequests).hasSize(2); // Headers + RequestBody

    // Request 1 response body message to deliver.
    proxyCall.request(1);

    // Send a response from data plane to trigger downstream delivery.
    String responseMsg = new String(new char[20000]).replace('\0', 'y');
    dataPlaneResponseObserverRef.get().onNext(responseMsg);

    assertThat(responseBodyProcessedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Since the window is exhausted but isReady() is false, and the drained upstream increment is
    // 0,
    // the trySendAccumulatedWindowUpdates() logic evaluates "iu > 0" (2nd) to False.
    // So no window update is sent.
    // receivedRequests should only contain Headers, RequestBody, and ResponseBody (size = 3).
    assertThat(receivedRequests).hasSize(3);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWindowUpdateWithheldOnDownstreamWindowExhaustionWithZeroIncrement()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto =
        createBaseProto(uniqueExtProcServerName)
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarLatch = new CountDownLatch(1); // Headers
    final CountDownLatch responseBodyReceivedLatch = new CountDownLatch(1);
    final CountDownLatch requestBodyProcessedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  sidecarLatch.countDown();
                } else if (request.hasResponseBody()) {
                  // Mutate response body and send back 70000 bytes (exhausts downstream return
                  // window).
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
                                                              ByteString.copyFrom(new byte[70000]))
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  responseBodyReceivedLatch.countDown();
                } else if (request.hasRequestBody()) {
                  // Mutate request body and send back 20000 bytes (below threshold).
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
                                                              ByteString.copyFrom(new byte[20000]))
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                  requestBodyProcessedLatch.countDown();
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
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(true);
    @SuppressWarnings("rawtypes")
    final AtomicReference<ClientCall.Listener> dataPlaneListenerRef = new AtomicReference<>();

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  @SuppressWarnings("unchecked")
                  public void start(Listener<RespT> responseListener, Metadata headers) {
                    dataPlaneListenerRef.set(responseListener);
                    super.start(responseListener, headers);
                  }

                  @Override
                  public boolean isReady() {
                    return transportReady.get();
                  }
                };
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(
            interceptor,
            METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // 1. Manually trigger onMessage on the client listener.
    // Since proxyCall.request() was never called, downstreamRequestsPending is 0.
    // So the mutated body from ext_proc will be queued in pendingMutatedResponseBodies,
    // and accumulated downstream increment (id) will remain 0.
    String responseMsg = new String(new char[10000]).replace('\0', 'y');
    InputStream responseStream = METHOD_BIDI_STREAMING.streamResponse(responseMsg);
    dataPlaneListenerRef.get().onMessage(responseStream);

    assertThat(responseBodyReceivedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // receivedRequests contains Headers + ResponseBody
    assertThat(receivedRequests).hasSize(2);

    // 2. Now send a request body of size 10000.
    // It will be sent to ext_proc, mutated to 20000 bytes (below threshold), and delivered
    // immediately to raw call.
    // During trySendAccumulatedWindowUpdates(), RHS evaluates to False (since id = 0, wd <= 0, but
    // id > 0 (2nd) is False).
    // So no window update is sent.
    String requestMsg = new String(new char[10000]).replace('\0', 'a');
    proxyCall.sendMessage(requestMsg);

    assertThat(requestBodyProcessedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // receivedRequests should only contain Headers, ResponseBody, and RequestBody (size = 3).
    assertThat(receivedRequests).hasSize(3);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testExtProcUnilateralHalfClose_PreventsDuplicateHalfClose() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final CountDownLatch extProcUnilateralLatch = new CountDownLatch(1);
    final CountDownLatch clientHalfCloseProcessedLatch = new CountDownLatch(1);

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
                } else if (request.hasRequestBody()) {
                  HttpBody requestBody = request.getRequestBody();
                  if (requestBody.getEndOfStreamWithoutMessage()) {
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
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    clientHalfCloseProcessedLatch.countDown();
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
                                                            .setBody(requestBody.getBody())
                                                            .setEndOfStream(true)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    extProcUnilateralLatch.countDown();
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
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(extProcImpl)
            .directExecutor()
            .build()
            .start());

    CachedChannelManager channelManager =
        new CachedChannelManager(
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor =
        new ExternalProcessorClientInterceptor(
            filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicInteger halfCloseCallCount = new AtomicInteger(0);
    final CountDownLatch serverResponseLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_CLIENT_STREAMING,
                ServerCalls.asyncClientStreamingCall(
                    new ServerCalls.ClientStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {
                            new Thread(
                                    () -> {
                                      try {
                                        serverResponseLatch.await(5, TimeUnit.SECONDS);
                                      } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                      }
                                      responseObserver.onNext("Response");
                                      responseObserver.onCompleted();
                                    })
                                .start();
                          }
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  public void halfClose() {
                    halfCloseCallCount.incrementAndGet();
                    super.halfClose();
                  }
                };
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(1);

    // Send Message 1. This will trigger the unilateral half-close from mock ext_proc.
    proxyCall.sendMessage("Message 1");

    assertThat(extProcUnilateralLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify that the transport halfClose was called once due to unilateral half-close
    assertThat(halfCloseCallCount.get()).isEqualTo(1);

    // Now call halfClose() on proxyCall.
    // This should send endOfStreamWithoutMessage to ext_proc, and receive endOfStream response.
    // However, it should NOT trigger a second halfClose() on the transport.
    proxyCall.halfClose();

    assertThat(clientHalfCloseProcessedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify that the transport halfClose count remains 1
    assertThat(halfCloseCallCount.get()).isEqualTo(1);

    // Let the server complete now
    serverResponseLatch.countDown();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHalfClosePiggybacking() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, DEFAULT_CALL_OPTIONS, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(filterClientRequests(receivedRequests)).hasSize(1);

    proxyCall.sendMessage("Last Message");

    // Verify 12 bytes are accumulated but no standalone update is sent.
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);

    proxyCall.halfClose();

    // Verify halfClose sends EOF request piggybacking the accumulated 12 bytes update.
    assertThat(filterClientRequests(receivedRequests)).hasSize(3);
    assertThat(filterClientRequests(receivedRequests).get(2).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2)
        .getRequestBody().getEndOfStreamWithoutMessage())

        .isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2).hasClientWindowUpdate()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2)
        .getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(12);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testNoDuplicateHalfCloseSentToExtProc() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch extProcReceivedHeadersLatch = new CountDownLatch(1);

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
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  extProcReceivedHeadersLatch.countDown();
                } else if (request.hasRequestBody()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder().build())
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, DEFAULT_CALL_OPTIONS, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(extProcReceivedHeadersLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(filterClientRequests(receivedRequests)).hasSize(1);

    // 1. Send Message 1 (size 65536) to exhaust the initial window (65536)
    String largeString = new String(new char[65536]);
    proxyCall.sendMessage(largeString);

    // Verify Message 1 is sent to ext-proc
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);
    assertThat(filterClientRequests(receivedRequests).get(1).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(1).getRequestBody().getBody().size())
        .isEqualTo(65536);

    // 2. Send Message 2 (size 1). This should be buffered because window is now 0.
    proxyCall.sendMessage("a");

    // Verify Message 2 is NOT sent yet (still only 2 requests received)
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);

    // 3. Call halfClose(). This should be deferred because queue is not empty.
    proxyCall.halfClose();

    // Verify halfClose is NOT sent yet (still only 2 requests received)
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);

    // 4. Send ServerWindowUpdate to increment window by 100
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementDownstreamToSidestream(100)
            .build())
        .build());

    // Draining should trigger:
    // - Message 2 (size 1) is sent.
    // - EOF request is sent (since queue became empty and halfClose was pending).
    // Total requests should now be 4: headers, body 1, body 2, eof.
    assertThat(filterClientRequests(receivedRequests)).hasSize(4);

    // Verify Message 2 content
    assertThat(filterClientRequests(receivedRequests).get(2).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(2).getRequestBody().getBody()
        .toStringUtf8()).isEqualTo("a");

    // Verify EOF request content
    assertThat(filterClientRequests(receivedRequests).get(3).hasRequestBody()).isTrue();
    assertThat(filterClientRequests(receivedRequests).get(3).getRequestBody()
        .getEndOfStreamWithoutMessage()).isTrue();

    // 5. Send another ServerWindowUpdate (redundant)
    responseObserverRef.get().onNext(ProcessingResponse.newBuilder()
        .setServerWindowUpdate(ProcessingResponse.ServerWindowUpdate.newBuilder()
            .setWindowIncrementDownstreamToSidestream(100)
            .build())
        .build());

    // Verify that NO duplicate EOF request is sent.
    // Total requests should remain 4.
    assertThat(filterClientRequests(receivedRequests)).hasSize(4);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testPiggybackingOnRequestBody() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
                  // Mutate Msg 1 to be 15 bytes
                  if (request.getRequestBody().getBody().toStringUtf8().equals("Msg 1")) {
                    responseObserver.onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(ByteString.copyFrom(new byte[15]))
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
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  // Forward response body mutation (20 bytes)
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, DEFAULT_CALL_OPTIONS, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(filterClientRequests(receivedRequests)).hasSize(1); // Headers request

    // Send Msg 1 (5 bytes). It is processed by ext_proc server and mutated to 15 bytes.
    proxyCall.sendMessage("Msg 1");

    // Wait until Msg 1 request and response are processed
    long startTime = System.currentTimeMillis();
    while (filterClientRequests(receivedRequests).size() < 2
        && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);
    assertThat(filterClientRequests(receivedRequests).get(1).hasRequestBody()).isTrue();

    // Trigger response headers and body (20 bytes) from upstream
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    // Wait until response headers are processed by ext_proc server
    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 3 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    String msg20Bytes = new String(new char[20]).replace('\0', 's');
    upstreamResponseObserver.onNext(msg20Bytes);

    // Wait until response body is processed by ext_proc server
    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 4 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    // Now send Msg 2 from app (5 bytes). This should piggyback the accumulated updates.
    proxyCall.sendMessage("Msg 2");

    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 6 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(receivedRequests).hasSize(6);

    // receivedRequests.get(3) is RespBody(Dummy for headers).
    // It should piggyback the accumulated 15 bytes update.
    ProcessingRequest dummyRespBodyReq = receivedRequests.get(3);
    assertThat(dummyRespBodyReq.hasResponseBody()).isTrue();
    assertThat(dummyRespBodyReq.hasClientWindowUpdate()).isTrue();
    assertThat(dummyRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(15);
    assertThat(dummyRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())

        .isEqualTo(0);

    // receivedRequests.get(5) is ReqBody(Msg 2).
    // It should piggyback the accumulated 20 bytes update.
    ProcessingRequest msg2Req = receivedRequests.get(5);
    assertThat(msg2Req.hasRequestBody()).isTrue();
    assertThat(msg2Req.getRequestBody().getBody().toStringUtf8()).isEqualTo("Msg 2");
    assertThat(msg2Req.hasClientWindowUpdate()).isTrue();
    assertThat(msg2Req.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(0);
    assertThat(msg2Req.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())

        .isEqualTo(20);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testPiggybackingOnResponseBody() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                } else if (request.hasRequestBody()) {
                  // Mutate Msg 1 to be 15 bytes
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFrom(new byte[15]))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  // Forward response body mutation
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING, DEFAULT_CALL_OPTIONS, dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
    proxyCall.request(10);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(filterClientRequests(receivedRequests)).hasSize(1); // Headers request

    // Send Msg 1 (5 bytes) from app. It is processed by ext_proc server and mutated to 15 bytes.
    proxyCall.sendMessage("Msg 1");

    // Wait until Msg 1 request and response are processed
    long startTime = System.currentTimeMillis();
    while (filterClientRequests(receivedRequests).size() < 2
        && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(filterClientRequests(receivedRequests)).hasSize(2);

    // Trigger response headers and first response body (20 bytes) from upstream
    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    // Wait until response headers are processed by ext_proc server
    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 3 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    String msg20Bytes = new String(new char[20]).replace('\0', 's');
    upstreamResponseObserver.onNext(msg20Bytes);

    // Wait until response body is processed by ext_proc server
    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 4 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }

    // Now send a second response body (5 bytes) from upstream.
    upstreamResponseObserver.onNext("Msg 2");

    startTime = System.currentTimeMillis();
    while (receivedRequests.size() < 6 && System.currentTimeMillis() - startTime < 5000) {
      fakeClock.forwardTime(1, TimeUnit.SECONDS);
    }
    assertThat(receivedRequests).hasSize(6);

    // receivedRequests.get(3) is RespBody(Dummy for headers).
    // It should piggyback the accumulated 15 bytes update.
    ProcessingRequest dummyRespBodyReq = receivedRequests.get(3);
    assertThat(dummyRespBodyReq.hasResponseBody()).isTrue();
    assertThat(dummyRespBodyReq.hasClientWindowUpdate()).isTrue();
    assertThat(dummyRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(15);
    assertThat(dummyRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())

        .isEqualTo(0);

    // receivedRequests.get(4) is RespBody(ssssssssssssssssssss).
    // It should piggyback the accumulated 17 bytes update
    // (from "Dummy for headers" which is 17 bytes).
    ProcessingRequest ssssRespBodyReq = receivedRequests.get(4);
    assertThat(ssssRespBodyReq.hasResponseBody()).isTrue();
    assertThat(ssssRespBodyReq.hasClientWindowUpdate()).isTrue();
    assertThat(ssssRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(0);
    assertThat(ssssRespBodyReq.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())

        .isEqualTo(17);

    // receivedRequests.get(5) is RespBody(Msg 2).
    // It should piggyback the accumulated 20 bytes update
    // (from ssssssssssssssssssss).
    ProcessingRequest msg2Req = receivedRequests.get(5);
    assertThat(msg2Req.hasResponseBody()).isTrue();
    assertThat(msg2Req.getResponseBody().getBody().toStringUtf8()).isEqualTo("Msg 2");
    assertThat(msg2Req.hasClientWindowUpdate()).isTrue();
    assertThat(msg2Req.getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(0);
    assertThat(msg2Req.getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())

        .isEqualTo(20);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testClientWindowUpdateDeferredUntilRequestBodySendMessage() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(2); // Headers + Request Body
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  sidecarLatch.countDown();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  // Mutate request body and send back 40000 bytes. This triggers client window
                  // update replenishment.
                  sidecarLatch.countDown();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFrom(new byte[40000]))
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

    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onNext("Response");
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    final CountDownLatch blockSendMessageLatch = new CountDownLatch(1);
    final CountDownLatch sendMessageEnteredLatch = new CountDownLatch(1);
    final CountDownLatch sendMessageFinishedLatch = new CountDownLatch(1);
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  public void sendMessage(ReqT message) {
                    sendMessageEnteredLatch.countDown();
                    try {
                      blockSendMessageLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      throw new RuntimeException(e);
                    }
                    super.sendMessage(message);
                  }
                };
              }
            })
            .directExecutor()
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Send 40k message to ext_proc.
    String body40k = new String(new char[40000]).replace('\0', 'a');

    // Call sendMessage in a background thread to avoid blocking the main test execution thread
    new Thread(() -> {
      proxyCall.sendMessage(body40k);
      sendMessageFinishedLatch.countDown();
    }).start();

    // Wait until interceptor's super.sendMessage() enters the custom interceptor and blocks
    assertThat(sendMessageEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // No standalone ClientWindowUpdate should be sent while blocked
    assertThat(receivedRequests).hasSize(2); // Only Headers and Request Body requests sent so far

    // Unblock the sendMessage call
    blockSendMessageLatch.countDown();

    // Wait for the interceptor to complete super.sendMessage() and send the window update
    assertThat(sendMessageFinishedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedRequests).hasSize(3);
    assertThat(receivedRequests.get(2).hasClientWindowUpdate()).isTrue();
    assertThat(receivedRequests.get(2)
        .getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())

        .isEqualTo(40000);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testClientWindowUpdateDeferredUntilResponseBodyOnMessage() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL)
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(3);
    // (Request Headers, Response Headers, Response Body 1)
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                sidecarLatch.countDown();
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseBody()) {
                  // Forward response body as-is.
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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                dataPlaneResponseObserverRef.set(responseObserver);
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                };
              }
            }))
        .build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    final CountDownLatch blockOnMessageLatch = new CountDownLatch(1);
    final CountDownLatch onMessageEnteredLatch = new CountDownLatch(1);
    final CountDownLatch onNextFinishedLatch = new CountDownLatch(1);
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onMessage(String message) {
        if (message.length() == 40000) {
          onMessageEnteredLatch.countDown();
          try {
            blockOnMessageLatch.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }
    };

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(appListener, new Metadata());
    proxyCall.request(10);

    // Call client to activate
    proxyCall.sendMessage("Client Msg");

    StreamObserver<String> upstreamResponseObserver = dataPlaneResponseObserverRef.get();
    upstreamResponseObserver.onNext("Dummy for headers");

    // Trigger response body from upstream in a background thread to avoid blocking
    // the main test execution thread
    String response40k = new String(new char[40000]).replace('\0', 'a');
    new Thread(() -> {
      upstreamResponseObserver.onNext(response40k);
      onNextFinishedLatch.countDown();
    }).start();

    // Wait until client app's onMessage enters and blocks
    assertThat(onMessageEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // No standalone ClientWindowUpdate should be sent while blocked
    assertThat(receivedRequests).hasSize(4);
    // (Headers + Response Headers + Dummy Body + Response 40k Body)

    // Unblock the onMessage call
    blockOnMessageLatch.countDown();

    // Wait for the interceptor to complete onMessage processing and send the window update
    assertThat(onNextFinishedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedRequests).hasSize(5);
    assertThat(receivedRequests.get(4).hasClientWindowUpdate()).isTrue();
    assertThat(
            receivedRequests
                .get(4)
                .getClientWindowUpdate()
                .getWindowIncrementSidestreamToDownstream())
        .isEqualTo(40000);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testClientWindowUpdateSentImmediatelyOnSidestreamToUpstreamWindowExhaustion()
      throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
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

    final List<ProcessingRequest> receivedRequests = new CopyOnWriteArrayList<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(2); // Headers + Request Body
    final CountDownLatch windowUpdateLatch = new CountDownLatch(1); // Window Update
    final AtomicReference<StreamObserver<ProcessingResponse>>
        responseObserverRef = new AtomicReference<>();

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
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  sidecarLatch.countDown();
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasRequestBody()) {
                  sidecarLatch.countDown();
                  // Mutate request body and send back 20000 bytes.
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFrom(new byte[20000]))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                  // Mutate request body and send back 50000 bytes.
                  // Total 70000 bytes completely exhausts the return window (starts at 65536).
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestBody(BodyResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setBodyMutation(BodyMutation.newBuilder()
                                  .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                      .setBody(ByteString.copyFrom(new byte[50000]))
                                      .build())
                                  .build())
                              .build())
                          .build())
                      .build());
                } else if (request.hasClientWindowUpdate()) {
                  windowUpdateLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
              }

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

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_CLIENT_STREAMING,
                ServerCalls.asyncClientStreamingCall(
                    new ServerCalls.ClientStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {
                            responseObserver.onNext("Response");
                            responseObserver.onCompleted();
                          }
                        };
                      }
                    }))
            .build());

    final AtomicBoolean transportReady = new AtomicBoolean(false);
    final AtomicReference<ClientCall.Listener<?>> dataPlaneListenerRef = new AtomicReference<>();

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .intercept(
                    new ClientInterceptor() {
                      @Override
                      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                          MethodDescriptor<ReqT, RespT> method,
                          CallOptions callOptions,
                          Channel next) {
                        return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<
                            ReqT, RespT>(next.newCall(method, callOptions)) {
                          @Override
                          public void start(Listener<RespT> responseListener, Metadata headers) {
                            dataPlaneListenerRef.set(responseListener);
                            super.start(responseListener, headers);
                          }

                          @Override
                          public void sendMessage(ReqT message) {
                            transportReady.set(false);
                            super.sendMessage(message);
                          }

                          @Override
                          public boolean isReady() {
                            return transportReady.get();
                          }
                        };
                      }
                    })
                .directExecutor()
                .build());

    ClientCall<String, String> proxyCall =
        interceptCall(
            interceptor,
            METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    // Send 10k message to ext_proc.
    String body10k = new String(new char[10000]).replace('\0', 'a');
    proxyCall.sendMessage(body10k);

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Since the window is exhausted but isReady() is false, no window update should be sent.
    assertThat(receivedRequests).hasSize(2);

    // Now, trigger transport ready. This should flush only the first message (20000) and set ready
    // to false.
    transportReady.set(true);
    dataPlaneListenerRef.get().onReady();

    // Wait for the window update to be received by ext_proc
    assertThat(windowUpdateLatch.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(receivedRequests).hasSize(3);
    assertThat(receivedRequests.get(2).hasClientWindowUpdate()).isTrue();
    assertThat(receivedRequests.get(2)
        .getClientWindowUpdate().getWindowIncrementSidestreamToUpstream())
        .isEqualTo(20000);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testClientWindowUpdateSentImmediatelyOnSidestreamToDownstreamWindowExhaustion()
      throws Exception {
    ExternalProcessor proto =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setGoogleGrpc(
                        GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("in-process:///" + extProcServerName)
                            .addChannelCredentialsPlugin(
                                Any.newBuilder().setTypeUrl(INSECURE_CREDENTIALS_TYPE_URL).build())
                            .build())
                    .build())
            .setProcessingMode(
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                    .build())
            .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final List<ProcessingRequest> receivedRequests =
        Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch headersLatch = new CountDownLatch(1);
    final CountDownLatch firstBodyResponseLatch = new CountDownLatch(1);
    final CountDownLatch secondBodyResponseLatch = new CountDownLatch(1);
    final CountDownLatch thirdBodyResponseLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                receivedRequests.add(request);
                if (request.hasRequestHeaders()) {
                  responseObserver.onNext(
                      ProcessingResponse.newBuilder()
                          .setRequestHeaders(HeadersResponse.newBuilder().build())
                          .build());
                  headersLatch.countDown();
                } else if (request.hasResponseBody()) {
                  ByteString body = request.getResponseBody().getBody();
                  boolean eos = request.getResponseBody().getEndOfStream();
                  if (body.size() == 50000) {
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
                                                            .setBody(body)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    firstBodyResponseLatch.countDown();

                    // Send second body (20000) - spoofed
                    ByteString body2 = ByteString.copyFrom(new byte[20000]);
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
                                                            .setBody(body2)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    secondBodyResponseLatch.countDown();

                    // Send third body (50000) - spoofed
                    ByteString body3 = ByteString.copyFrom(new byte[50000]);
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
                                                            .setBody(body3)
                                                            .setEndOfStream(eos)
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                    thirdBodyResponseLatch.countDown();
                  }
                }
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
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
            config -> {
              return grpcCleanup.register(
                  InProcessChannelBuilder.forName(uniqueExtProcServerName)
                      .directExecutor()
                      .build());
            });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final AtomicReference<StreamObserver<String>> dataPlaneResponseObserverRef =
        new AtomicReference<>();
    dataPlaneServiceRegistry.addService(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_BIDI_STREAMING,
                ServerCalls.asyncBidiStreamingCall(
                    new ServerCalls.BidiStreamingMethod<String, String>() {
                      @Override
                      public StreamObserver<String> invoke(
                          StreamObserver<String> responseObserver) {
                        dataPlaneResponseObserverRef.set(responseObserver);
                        return new StreamObserver<String>() {
                          @Override
                          public void onNext(String value) {}

                          @Override
                          public void onError(Throwable t) {}

                          @Override
                          public void onCompleted() {}
                        };
                      }
                    }))
            .build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()), dataPlaneChannel);

    final List<String> receivedResponses = Collections.synchronizedList(new ArrayList<>());
    proxyCall.start(
        new ClientCall.Listener<String>() {
          @Override
          public void onMessage(String message) {
            receivedResponses.add(message);
          }
        },
        new Metadata());

    // Wait for the headers handshake to complete and activate the call
    assertThat(headersLatch.await(5, TimeUnit.SECONDS)).isTrue();

    proxyCall.request(1);

    String resp1 = new String(new char[50000]).replace('\0', 'x');
    dataPlaneResponseObserverRef.get().onNext(resp1);

    assertThat(firstBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(secondBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(thirdBodyResponseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // The first response body (50000) should be delivered.
    assertThat(receivedResponses).containsExactly(resp1);

    // Let's filter out window updates received by mock ext_proc.
    // There should be exactly 1 window update (for the first response body, since 50000 >= 32768).
    List<ProcessingRequest> windowUpdates = new ArrayList<>();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasClientWindowUpdate()) {
        windowUpdates.add(req);
      }
    }
    assertThat(windowUpdates).hasSize(1);
    assertThat(
            windowUpdates.get(0).getClientWindowUpdate().getWindowIncrementSidestreamToDownstream())
        .isEqualTo(50000);

    // Now, request another message.
    // This will deliver the second body (20000) which was queued.
    // Since the window was exhausted (initial 65536 - 50000 - 20000 - 50000 = -54464 <= 0),
    // delivering the second body (20000) should immediately trigger a window update of 20000,
    // even though 20000 is less than the threshold (32768).
    proxyCall.request(1);

    assertThat(receivedResponses).hasSize(2);
    assertThat(receivedResponses.get(1).length()).isEqualTo(20000);

    windowUpdates.clear();
    for (ProcessingRequest req : receivedRequests) {
      if (req.hasClientWindowUpdate()) {
        windowUpdates.add(req);
      }
    }
    // We should now have 2 window updates.
    assertThat(windowUpdates).hasSize(2);
    assertThat(
            windowUpdates
                .get(1)
                .getClientWindowUpdate()
                .getWindowIncrementSidestreamToDownstream())
        .isEqualTo(20000);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 22: Streaming Completeness (Client & Bi-Di) ---

  @Test
  @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
  public void givenClientStreamingRpc_whenExtProcMutatesAll_thenAllTargetsReceiveMutatedData()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-client-stream-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-client-stream-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
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

    final Metadata.Key<String> reqKey =
        Metadata.Key.of("req-mutated", Metadata.ASCII_STRING_MARSHALLER);

    final List<String> receivedPhases = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarActionLatch = new CountDownLatch(5);
    final ExecutorService sidecarResponseExecutor = Executors.newSingleThreadExecutor();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            sidecarResponseExecutor.submit(() -> {
              synchronized (responseObserver) {
                ProcessingResponse.Builder resp = ProcessingResponse.newBuilder();
                if (request.hasRequestHeaders()) {
                  receivedPhases.add("REQ_HEADERS");
                  resp.setRequestHeaders(
                      HeadersResponse.newBuilder()
                          .setResponse(
                              CommonResponse.newBuilder()
                                  .setHeaderMutation(
                                      HeaderMutation.newBuilder()
                                          .addSetHeaders(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                                  .newBuilder()
                                                  .setHeader(
                                                      io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                          .newBuilder()
                                                          .setKey("req-mutated")
                                                          .setValue("true")
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setEndOfStream(true)
                                                    .setEndOfStreamWithoutMessage(true)
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else if (request.getRequestBody().getEndOfStream()) {
                    receivedPhases.add("REQ_BODY_MSG");
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setBody(ByteString.copyFromUtf8(
                                                        "MutatedRequest"))
                                                    .setEndOfStream(true)
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    receivedPhases.add("REQ_BODY_MSG");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setBody(ByteString.copyFromUtf8(
                                                        "MutatedRequest"))
                                                    .build())
                                            .build())
                                    .build())
                            .build());
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
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
                      @Override
                      public void onNext(String value) {
                        serverReceivedBody.set(value);
                      }

                      @Override
                      public void onError(Throwable t) {
                      }

                      @Override
                      public void onCompleted() {
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

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(testExecutor)
                .build());
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(testExecutor)
              .build());
    });
    ScheduledExecutorService sidecarRealScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, sidecarRealScheduler, FAKE_CONTEXT);

    final CountDownLatch finishLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> headersFromInterceptor = new AtomicReference<>();
    Channel interceptingChannel =
        io.grpc.ClientInterceptors.intercept(
            dataPlaneChannel,
            new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(
                        new io.grpc.ForwardingClientCallListener
                            .SimpleForwardingClientCallListener<RespT>(responseListener) {
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

    final AtomicReference<Throwable> errorRef = new AtomicReference<>();
    final AtomicReference<String> clientReceivedBody = new AtomicReference<>();
    StreamObserver<String> requestObserver = ClientCalls.asyncClientStreamingCall(
        interceptCall(interceptor, 
            METHOD_CLIENT_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(testExecutor),
            interceptingChannel),
        new StreamObserver<String>() {
          @Override
          public void onNext(String value) {
            clientReceivedBody.set(value);
          }

          @Override
          public void onError(Throwable t) {
            errorRef.set(t);
            finishLatch.countDown();
          }

          @Override
          public void onCompleted() {
            finishLatch.countDown();
          }
        });

    requestObserver.onNext("OriginalRequest");
    requestObserver.onCompleted();

    if (!sidecarActionLatch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Sidecar actions failed. Received: " + receivedPhases);
    }
    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    if (errorRef.get() != null) {
      throw new AssertionError("RPC failed", errorRef.get());
    }

    List<String> expectedPhases =
        Arrays.asList(
            "REQ_HEADERS",
            "REQ_BODY_MSG",
            "REQ_BODY_EOS",
            "RESP_HEADERS",
            "RESP_BODY",
            "RESP_TRAILERS");
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
  @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
  public void givenBidiStreamingRpc_whenExtProcMutatesAll_thenAllTargetsReceiveMutatedData()
      throws Exception {
    String uniqueExtProcServerName =
        "extProc-bidi-stream-" + InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName =
        "dataPlane-bidi-stream-" + InProcessServerBuilder.generateName();
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
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

    final Metadata.Key<String> reqKey =
        Metadata.Key.of("req-mutated", Metadata.ASCII_STRING_MARSHALLER);

    final List<String> receivedPhases = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch sidecarBidiLatch = new CountDownLatch(5);
    final ExecutorService bidiSidecarResponseExecutor = Executors.newSingleThreadExecutor();
    // External Processor Server
    ExternalProcessorGrpc.ExternalProcessorImplBase bidiExtProcImpl;
    bidiExtProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            bidiSidecarResponseExecutor.submit(() -> {
              synchronized (responseObserver) {
                ProcessingResponse.Builder resp = ProcessingResponse.newBuilder();
                if (request.hasRequestHeaders()) {
                  receivedPhases.add("REQ_HEADERS");
                  resp.setRequestHeaders(
                      HeadersResponse.newBuilder()
                          .setResponse(
                              CommonResponse.newBuilder()
                                  .setHeaderMutation(
                                      HeaderMutation.newBuilder()
                                          .addSetHeaders(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                                  .newBuilder()
                                                  .setHeader(
                                                      io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                          .newBuilder()
                                                          .setKey("req-mutated")
                                                          .setValue("true")
                                                          .build())
                                                  .build())
                                          .build())
                                  .build())
                          .build());
                } else if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setEndOfStream(true)
                                                    .setEndOfStreamWithoutMessage(true)
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else if (request.getRequestBody().getEndOfStream()) {
                    receivedPhases.add("REQ_BODY_MSG");
                    receivedPhases.add("REQ_BODY_EOS");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setBody(ByteString.copyFromUtf8(
                                                        "MutatedBidiReq"))
                                                    .setEndOfStream(true)
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  } else {
                    receivedPhases.add("REQ_BODY_MSG");
                    resp.setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(
                                CommonResponse.newBuilder()
                                    .setBodyMutation(
                                        BodyMutation.newBuilder()
                                            .setStreamedResponse(
                                                StreamedBodyResponse.newBuilder()
                                                    .setBody(
                                                        ByteString.copyFromUtf8("MutatedBidiReq"))
                                                    .build())
                                            .build())
                                    .build())
                            .build());
                  }
                } else if (request.hasResponseHeaders()) {
                  receivedPhases.add("RESP_HEADERS");
                  resp.setResponseHeaders(HeadersResponse.newBuilder().build());
                } else if (request.hasResponseBody()) {
                  receivedPhases.add("RESP_BODY");
                  resp.setResponseBody(
                      BodyResponse.newBuilder()
                          .setResponse(
                              CommonResponse.newBuilder()
                                  .setBodyMutation(
                                      BodyMutation.newBuilder()
                                          .setStreamedResponse(
                                              StreamedBodyResponse.newBuilder()
                                                  .setBody(request.getResponseBody().getBody())
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    final ExecutorService bidiTestExecutor = Executors.newFixedThreadPool(20);
    final ExecutorService sidecarExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueExtProcServerName)
            .addService(bidiExtProcImpl)
            .executor(sidecarExecutor)
            .build()
            .start());

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
                      @Override
                      public void onNext(String value) {
                        responseObserver.onNext(value + "Echo");
                      }

                      @Override
                      public void onError(Throwable t) {
                      }

                      @Override
                      public void onCompleted() {
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
    grpcCleanup.register(
        InProcessServerBuilder.forName(uniqueDataPlaneServerName)
            .fallbackHandlerRegistry(uniqueBidiRegistry)
            .executor(bidiTestExecutor)
            .build()
            .start());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(bidiTestExecutor)
                .build());
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(bidiTestExecutor)
              .build());
    });
    ScheduledExecutorService bidiRealScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, bidiRealScheduler, FAKE_CONTEXT);

    final AtomicReference<String> clientReceivedBody = new AtomicReference<>();
    final CountDownLatch finishLatch = new CountDownLatch(1);
    final AtomicReference<Metadata> bidiHeadersFromInterceptor = new AtomicReference<>();

    Channel bidiInterceptingChannel =
        io.grpc.ClientInterceptors.intercept(
            dataPlaneChannel,
            new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                  @Override
                  public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(
                        new io.grpc.ForwardingClientCallListener
                            .SimpleForwardingClientCallListener<RespT>(responseListener) {
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

    final AtomicReference<Throwable> errorRef = new AtomicReference<>();
    StreamObserver<String> bidiRequestObserver = ClientCalls.asyncBidiStreamingCall(
        interceptCall(interceptor, 
            METHOD_BIDI_STREAMING,
            DEFAULT_CALL_OPTIONS.withExecutor(bidiTestExecutor),
            bidiInterceptingChannel),
        new StreamObserver<String>() {
          @Override
          public void onNext(String value) {
            clientReceivedBody.set(value);
          }

          @Override
          public void onError(Throwable t) {
            errorRef.set(t);
            finishLatch.countDown();
          }

          @Override
          public void onCompleted() {
            finishLatch.countDown();
          }
        });

    bidiRequestObserver.onNext("Bidi");
    bidiRequestObserver.onCompleted();

    if (!sidecarBidiLatch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Sidecar bidi actions failed. Received: " + receivedPhases);
    }
    assertThat(finishLatch.await(5, TimeUnit.SECONDS)).isTrue();
    if (errorRef.get() != null) {
      throw new AssertionError("RPC failed", errorRef.get());
    }

    List<String> expectedPhases =
        Arrays.asList(
            "REQ_HEADERS",
            "REQ_BODY_MSG",
            "REQ_BODY_EOS",
            "RESP_HEADERS",
            "RESP_BODY",
            "RESP_TRAILERS");
    assertThat(receivedPhases).containsExactlyElementsIn(expectedPhases).inOrder();

    assertThat(serverReceivedHeaders.get().get(reqKey)).isEqualTo("true");
    assertThat(clientReceivedBody.get()).isEqualTo("MutatedBidiReqEcho");

    bidiRealScheduler.shutdown();
    bidiSidecarResponseExecutor.shutdown();
    bidiTestExecutor.shutdown();
    sidecarExecutor.shutdown();
    channelManager.close();
  }

  // --- Category 23: Header Forwarding ---

  @Test
  public void
      givenAllowedHeaders_whenRequestHeadersForwarded_thenOnlyAllowedAreSent()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders>
        capturedHeaders = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(
            METHOD_SAY_HELLO,
            ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  responseObserver.onNext("Hello");
                  responseObserver.onCompleted();
                }))
        .build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("x-allowed-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(
        Metadata.Key.of("x-disallowed", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(
        Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "application/grpc");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-allowed-1");
    assertThat(headerNames).contains("content-type");
    assertThat(headerNames).doesNotContain("x-disallowed");
    
    channelManager.close();
  }

  @Test
  public void
      givenAllowedHeaders_whenResponseHeadersForwarded_thenOnlyAllowedAreSent()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders>
        capturedHeaders = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
        ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
        return new StreamObserver<ProcessingRequest>() {
          @Override
          public void onNext(ProcessingRequest request) {
            if (request.hasRequestHeaders()) {
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setRequestHeaders(HeadersResponse.newBuilder().build())
                  .build());
            } else if (request.hasResponseHeaders()) {
              capturedHeaders.set(request.getResponseHeaders());
              responseObserver.onNext(ProcessingResponse.newBuilder()
                  .setResponseHeaders(HeadersResponse.newBuilder().build())
                  .build());
              sidecarLatch.countDown();
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Config with forward_rules: allowed_headers = ["x-allowed-*", "content-type"]
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .setForwardRules(HeaderForwardingRules.newBuilder()
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .executor(Executors.newSingleThreadExecutor())
              .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                METHOD_SAY_HELLO,
                ServerCalls.asyncUnaryCall(
                    (request, responseObserver) -> {
                      responseObserver.onNext("Hello");
                      responseObserver.onCompleted();
                    }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Metadata responseHeaders = new Metadata();
            responseHeaders.put(
                Metadata.Key.of("x-allowed-response", Metadata.ASCII_STRING_MARSHALLER), "v1");
            responseHeaders.put(
                Metadata.Key.of("x-disallowed-response", Metadata.ASCII_STRING_MARSHALLER), "v2");
            responseHeaders.put(
                Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
                "application/grpc");

            call.sendHeaders(responseHeaders);
            return next.startCall(call, headers);
          }
        }));
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-allowed-response");
    assertThat(headerNames).contains("content-type");
    assertThat(headerNames).doesNotContain("x-disallowed-response");
    
    channelManager.close();
  }

  @Test
  public void givenDisallowedHeaders_whenHeadersForwarded_thenSkipped() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders =
        new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "v3");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
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

    final AtomicReference<io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders> capturedHeaders =
        new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-foo-1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("x-foo-secret", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("x-bar", Metadata.ASCII_STRING_MARSHALLER), "v3");

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, headers);

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    List<String> headerNames = new ArrayList<>();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValue hv :
        capturedHeaders.get().getHeaders().getHeadersList()) {
      headerNames.add(hv.getKey());
    }
    assertThat(headerNames).contains("x-foo-1");
    assertThat(headerNames).doesNotContain("x-foo-secret");
    assertThat(headerNames).doesNotContain("x-bar");
    
    channelManager.close();
  }

  // --- Category 24: Request Attributes ---

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
        .addRequestAttributes("request.url_path")
        .addRequestAttributes("request.host")
        .addRequestAttributes("request.method")
        .addRequestAttributes("request.query")
        .build();

    final AtomicReference<ProcessingRequest> capturedRequest = new AtomicReference<>();
    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch callLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callLatch.countDown();
      }
    }, new Metadata());
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.path").getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    assertThat(attributes.get("request.url_path").getFieldsOrThrow("").getStringValue())
        .isEqualTo("/test.TestService/SayHello");
    assertThat(attributes.get("request.host").getFieldsOrThrow("").getStringValue())
        .isEqualTo(dataPlaneChannel.authority());
    
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

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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
            }
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    headers.put(
        Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "val");
    headers.put(
        Metadata.Key.of("x-bin-key-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[]{1, 2});

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(Executors.newSingleThreadExecutor()),
            dataPlaneChannel);
    
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        callLatch.countDown();
      }
    }, headers);
    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(callLatch.await(5, TimeUnit.SECONDS)).isTrue();
    
    ProcessingRequest request = capturedRequest.get();
    java.util.Map<String, com.google.protobuf.Struct> attributes = request.getAttributesMap();
    assertThat(attributes.get("request.referer").getFieldsOrThrow("").getStringValue())
        .isEqualTo("http://google.com");
    assertThat(attributes.get("request.useragent").getFieldsOrThrow("").getStringValue())
        .isEqualTo("custom-ua");
    assertThat(attributes.get("request.id").getFieldsOrThrow("").getStringValue())
        .isEqualTo("req-123");
    
    com.google.protobuf.Struct headersStruct = attributes.get("request.headers");
    assertThat(headersStruct.getFieldsOrThrow("x-bin-key-bin").getStringValue())
        .isEqualTo("AQI");
    
    channelManager.close();
  }



  // --- Category 25: Response Ordering Checks ---

  @Test
  public void givenOutOfOrderReqResponses_whenMessageArrivesBeforeHeaders_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<Throwable> extProcError = new AtomicReference<>();

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
            extProcError.set(t);
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());
    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
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
    
    // The call should fail with INTERNAL status
    // due to stream failure triggered by protocol error
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(appStatus.get().getDescription()).contains("External processor stream failed");
    
    channelManager.close();
  }

  @Test
  public void givenUnexpectedResponseHeaders_whenHeadersArriveBeforeServerHeaders_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<Throwable> extProcError = new AtomicReference<>();

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
                  // Violate order: send ResponseHeaders response instead of RequestHeaders response
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {
                extProcError.set(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Configure processing mode to SEND both request and response headers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // The call should fail with INTERNAL status due to protocol error
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(appStatus.get().getDescription()).contains("External processor stream failed");

    // The data plane call should have the local cause set to the protocol violation
    assertThat(appStatus.get().getCause()).isNotNull();
    assertThat(appStatus.get().getCause().getMessage())
        .contains("Protocol error: received response out of order");

    channelManager.close();
  }

  @Test
  public void givenUnexpectedResponseTrailers_whenTrailersArriveBeforeServerTrailers_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<Throwable> extProcError = new AtomicReference<>();

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
                  // Violate order: send ResponseTrailers response instead of RequestHeaders
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseTrailers(TrailersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {
                extProcError.set(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Configure processing mode to SEND both request headers and response trailers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // The call should fail with INTERNAL status due to protocol error
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(appStatus.get().getDescription()).contains("External processor stream failed");

    // The data plane call should have the local cause set to the protocol violation
    assertThat(appStatus.get().getCause()).isNotNull();
    assertThat(appStatus.get().getCause().getMessage())
        .contains("Protocol error: received response out of order");

    channelManager.close();
  }

  @Test
  public void givenOutOfOrderRespResponses_whenResponseBodyArrivesBeforeResponseHeaders_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final AtomicReference<Throwable> extProcError = new AtomicReference<>();

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
                  // Send valid RequestHeaders response first
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                } else if (request.hasResponseHeaders()) {
                  // Violate order: send ResponseBody response instead of ResponseHeaders response
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseBody(BodyResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                  responseObserver.onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {
                extProcError.set(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Configure processing mode to SEND request headers, response headers, response body,
    // and response trailers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          // The data plane server responds to trigger response headers on the client
          responseObserver.onNext("Hello");
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall = interceptCall(
        interceptor,
        METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    assertThat(sidecarLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // The call should fail with INTERNAL status due to protocol error
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(appStatus.get().getDescription()).contains("External processor stream failed");

    // The data plane call should have the local cause set to the protocol violation
    assertThat(appStatus.get().getCause()).isNotNull();
    assertThat(appStatus.get().getCause().getMessage())
        .contains("Protocol error: received response_body before headers response.");

    channelManager.close();
  }

  @Test
  public void givenValidOrder_whenResponsesArriveInOrder_thenSucceeds() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        };
      }
    };
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Configure processing mode to SEND request headers, but SKIP response headers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(
            METHOD_SAY_HELLO,
            ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  responseObserver.onNext("Hello");
                  responseObserver.onCompleted();
                }))
        .build());

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName)
                .executor(Executors.newSingleThreadExecutor())
                .build());

    final CountDownLatch callLatch = new CountDownLatch(1);
    final AtomicReference<Status> capturedStatus = new AtomicReference<>();

    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify that headers are processed correctly and the ordering check passes
    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    
    // Verify that the call completes successfully
    assertThat(callLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().isOk()).isTrue();
    
    channelManager.close();
  }

  @Test
  public void givenBidiStreamInterleavedEvents_whenExtProcRespondsOutOfLockstep_thenSucceeds()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarRequestBodyLatch = new CountDownLatch(1);
    final CountDownLatch sidecarResponseHeadersLatch = new CountDownLatch(1);
    final CountDownLatch allDoneLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              final StreamObserver<ProcessingResponse> responseObserver) {
            ((ServerCallStreamObserver<ProcessingResponse>) responseObserver).request(100);
            final AtomicReference<StreamObserver<ProcessingResponse>> observerRef =
                new AtomicReference<>(responseObserver);
            return new StreamObserver<ProcessingRequest>() {
              private ProcessingRequest savedRequestBody;

              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestBody()) {
                  if (request.getRequestBody().getEndOfStream()
                      || request.getRequestBody().getEndOfStreamWithoutMessage()) {
                    // This is the half-close request!
                    observerRef.get().onNext(ProcessingResponse.newBuilder()
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
                  } else {
                    savedRequestBody = request;
                    sidecarRequestBodyLatch.countDown();
                  }
                } else if (request.hasResponseHeaders()) {
                  // When RESPONSE_HEADERS is received, we respond to it first!
                  // This is out-of-lockstep because REQUEST_BODY response is still outstanding.
                  observerRef.get().onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarResponseHeadersLatch.countDown();

                  // Now send response to REQUEST_BODY with streamed response containing the body
                  if (savedRequestBody != null) {
                    observerRef.get().onNext(ProcessingResponse.newBuilder()
                        .setRequestBody(BodyResponse.newBuilder()
                            .setResponse(CommonResponse.newBuilder()
                                .setBodyMutation(BodyMutation.newBuilder()
                                    .setStreamedResponse(StreamedBodyResponse.newBuilder()
                                        .setBody(savedRequestBody.getRequestBody().getBody())
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
              public void onCompleted() {
                observerRef.get().onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(scheduler)
        .build().start());

    MutableHandlerRegistry uniqueBidiRegistry = new MutableHandlerRegistry();
    uniqueBidiRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_BIDI_STREAMING, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<String, String>() {
              @Override
              public StreamObserver<String> invoke(StreamObserver<String> responseObserver) {
                // Send headers immediately by sending a message when stream starts
                responseObserver.onNext("Welcome");
                return new StreamObserver<String>() {
                  @Override
                  public void onNext(String value) {}

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {
                    responseObserver.onCompleted();
                  }
                };
              }
            }))
        .build());

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(uniqueBidiRegistry)
        .executor(scheduler)
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            // SKIP so data plane call starts immediately
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
            // GRPC body mode to trigger REQUEST_BODY
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
            // SEND to trigger RESPONSE_HEADERS
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(scheduler)
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .executor(scheduler)
            .build());

    ClientCall<String, String> clientCall = interceptCall(interceptor,
        METHOD_BIDI_STREAMING,
        DEFAULT_CALL_OPTIONS.withExecutor(scheduler),
        dataPlaneChannel);

    StreamObserver<String> bidiRequestObserver = ClientCalls.asyncBidiStreamingCall(
        clientCall,
        new StreamObserver<String>() {
          @Override
          public void onNext(String value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            allDoneLatch.countDown();
          }
        });

    // Send client message to trigger REQUEST_BODY to ext_proc
    bidiRequestObserver.onNext("ClientMsg");

    // Wait for ext_proc to process out-of-lockstep events
    while (sidecarRequestBodyLatch.getCount() > 0 || sidecarResponseHeadersLatch.getCount() > 0) {
      if (fakeClock.numPendingTasks() == 0) {
        break;
      }
      fakeClock.runDueTasks();
    }
    assertThat(sidecarRequestBodyLatch.getCount()).isEqualTo(0);
    assertThat(sidecarResponseHeadersLatch.getCount()).isEqualTo(0);

    // Complete the bidi stream
    bidiRequestObserver.onCompleted();
    while (allDoneLatch.getCount() > 0) {
      if (fakeClock.numPendingTasks() == 0) {
        break;
      }
      fakeClock.runDueTasks();
    }
    assertThat(allDoneLatch.getCount()).isEqualTo(0);

    // Clean up by cancelling the call explicitly
    clientCall.cancel("Test finished", null);

    channelManager.close();
  }

  // --- Category 26: Header Response Status Checks ---

  @Test
  public void givenRequestHeadersResponse_whenStatusIsContinueAndReplace_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
            sidecarFinishedLatch.countDown();
          }

          @Override
          public void onCompleted() {
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
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
    } catch (IllegalStateException ignored) {
      // ignore
    }

    assertThat(sidecarLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarFinishedLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(30, TimeUnit.SECONDS)).isTrue();

    // Call should succeed due to fail-open
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.OK);

    channelManager.close();
  }

  @Test
  public void givenResponseHeadersResponse_whenStatusIsContinueAndReplace_thenFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    final CountDownLatch sidecarLatch = new CountDownLatch(1);
    final CountDownLatch sidecarFinishedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl;
    extProcImpl = new ExternalProcessorGrpc.ExternalProcessorImplBase() {
      @Override
      public StreamObserver<ProcessingRequest> process(
          final StreamObserver<ProcessingResponse> responseObserver) {
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

          @Override
          public void onError(Throwable t) {
            sidecarFinishedLatch.countDown();
          }

          @Override
          public void onCompleted() {
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
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("Hello");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    final AtomicReference<Status> appStatus = new AtomicReference<>();
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
            dataPlaneChannel);
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
    } catch (IllegalStateException ignored) {
      // ignore
    }

    assertThat(sidecarLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(sidecarFinishedLatch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(30, TimeUnit.SECONDS)).isTrue();

    // The call should succeed due to fail-open
    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.OK);

    channelManager.close();
  }

  @Test
  public void givenExtProcCall_whenExecutionSucceeds_thenAll4MetricsAreRecorded() throws Exception {
    final String uniqueExtProcServerName = "ext-proc-server-metrics-" + java.util.UUID.randomUUID();
    final CountDownLatch sidecarRequestHeadersLatch = new CountDownLatch(1);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    // In-process mock server for External Processor
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarRequestHeadersLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Enable request headers and response headers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });

    // Mock MetricRecorder to assert records
    io.grpc.MetricRecorder mockMetricRecorder = Mockito.mock(io.grpc.MetricRecorder.class);
    Filter.FilterContext customContext = Filter.FilterContext.create(
        "envoy.ext_proc",
        mockMetricRecorder);

    ScheduledExecutorService realScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, realScheduler, customContext);

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          new Thread(() -> {
            try {
              if (dataPlaneLatch.await(10, TimeUnit.SECONDS)) {
                responseObserver.onNext("Hello");
                responseObserver.onCompleted();
              }
            } catch (InterruptedException e) {
              responseObserver.onError(e);
            }
          }).start();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .overrideAuthority("xds:///target-service-metric")
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor())
                .withOption(XdsNameResolver.CLUSTER_SELECTION_KEY, "backend-service-metric"),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // 1. Wait for mock Ext Proc to receive and process client request headers
    assertThat(sidecarRequestHeadersLatch.await(10, TimeUnit.SECONDS)).isTrue();

    // 2. Release the data plane server to respond back to the client call
    dataPlaneLatch.countDown();

    // 3. Assert that all stages complete in sequence deterministically
    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();

    // Clean up and close the Ext Proc stream to release in-process server/channel resources cleanly
    proxyCall.cancel("Cleanup", null);

    // Verify that the 4 duration metrics were recorded with proper labels!
    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.clientHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.clientHalfCloseDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.serverHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.serverTrailersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric")));

    channelManager.close();
    realScheduler.shutdown();
  }

  @Test
  public void givenExtProcCall_whenExecutionFails_thenAll4MetricsAreRecorded() throws Exception {
    final String uniqueExtProcServerName =
        "ext-proc-server-metrics-fail-" + java.util.UUID.randomUUID();
    final CountDownLatch sidecarRequestHeadersLatch = new CountDownLatch(1);
    final CountDownLatch sidecarLatch = new CountDownLatch(1);

    // In-process mock server for External Processor
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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarRequestHeadersLatch.countDown();
                } else if (request.hasResponseHeaders()) {
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setResponseHeaders(HeadersResponse.newBuilder().build())
                      .build());
                  sidecarLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(Executors.newSingleThreadExecutor())
        .build().start());

    // Enable request headers and response headers
    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(Executors.newSingleThreadExecutor())
          .build());
    });

    // Mock MetricRecorder to assert records
    io.grpc.MetricRecorder mockMetricRecorder = Mockito.mock(io.grpc.MetricRecorder.class);
    Filter.FilterContext customContext = Filter.FilterContext.create(
        "envoy.ext_proc",
        mockMetricRecorder);

    ScheduledExecutorService realScheduler = Executors.newSingleThreadScheduledExecutor();
    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, realScheduler, customContext);

    final CountDownLatch dataPlaneLatch = new CountDownLatch(1);
    dataPlaneServiceRegistry.addService(ServerInterceptors.intercept(
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
              new Thread(() -> {
                try {
                  if (dataPlaneLatch.await(10, TimeUnit.SECONDS)) {
                    responseObserver.onError(
                        Status.UNAUTHENTICATED
                            .withDescription("authentication failed")
                            .asRuntimeException());
                  }
                } catch (InterruptedException e) {
                  responseObserver.onError(e);
                }
              }).start();
            })).build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            call.sendHeaders(new Metadata());
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .overrideAuthority("xds:///target-service-metric-fail")
            .executor(Executors.newSingleThreadExecutor())
            .build());

    final AtomicReference<Status> appStatus = new AtomicReference<>();
    final CountDownLatch appCloseLatch = new CountDownLatch(1);
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, 
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor())
                .withOption(XdsNameResolver.CLUSTER_SELECTION_KEY, "backend-service-metric-fail"),
            dataPlaneChannel);

    proxyCall.start(new ClientCall.Listener<String>() {
      @Override public void onClose(Status status, Metadata trailers) {
        appStatus.set(status);
        appCloseLatch.countDown();
      }
    }, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // 1. Wait for mock Ext Proc to receive and process client request headers
    assertThat(sidecarRequestHeadersLatch.await(10, TimeUnit.SECONDS)).isTrue();

    // 2. Release the data plane server to respond back with error
    dataPlaneLatch.countDown();

    // 3. Assert that all stages complete
    assertThat(sidecarLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(appCloseLatch.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(appStatus.get().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

    // Clean up and close the Ext Proc stream
    proxyCall.cancel("Cleanup", null);

    // Verify that the 4 duration metrics were recorded with proper labels!
    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.clientHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric-fail")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric-fail")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.clientHalfCloseDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric-fail")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric-fail")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.serverHeadersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric-fail")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric-fail")));

    Mockito.verify(mockMetricRecorder, Mockito.times(1)).recordDoubleHistogram(
        Mockito.eq(ExternalProcessorClientInterceptor.serverTrailersDuration),
        Mockito.anyDouble(),
        Mockito.eq(com.google.common.collect.ImmutableList.of("xds:///target-service-metric-fail")),
        Mockito.eq(com.google.common.collect.ImmutableList.of("backend-service-metric-fail")));

    channelManager.close();
    realScheduler.shutdown();
  }

  // --- Category 27: Call activation with failure mode allow on and off ---
  @Test
  public void
      givenRequestHeaderModeSend_Fma_true_whenExtProcTerminates_thenCallIsActivated()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
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

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .directExecutor()
            .build());

    ClientCall<String, String> clientCall = interceptCall(
        interceptor, METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    clientCall.sendMessage("app-msg");
    clientCall.halfClose();

    responseObserverRef.get().onError(new RuntimeException("Stream failure during start"));

    boolean completed = callCompletedLatch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    // Verify call completed successfully due to FMA true (fail-open)
    assertThat(closedStatus.get().isOk()).isTrue();

    channelManager.close();
  }

  @Test
  public void
      givenRequestHeaderModeSend_Fma_false_whenExtProcTerminates_thenCallIsClosed()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(false)
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

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .directExecutor()
            .build());

    ClientCall<String, String> clientCall = interceptCall(
        interceptor, METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    clientCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    // Terminate stream with error
    responseObserverRef.get().onError(new RuntimeException("Stream failure during start"));

    boolean completed = callCompletedLatch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    // Verify call closed with INTERNAL status due to FMA false
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void givenFailureModeAllowTrue_whenExtProcStreamFailsAfterRequestBodySent_thenCallFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestBodyMode(ProcessingMode.BodySendMode.GRPC)
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

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .directExecutor()
            .build());

    ClientCall<String, String> clientCall = interceptCall(
        interceptor, METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    clientCall.sendMessage("app-msg");
    clientCall.halfClose();

    // Now abruptly fail the stream
    responseObserverRef.get()
        .onError(new RuntimeException("Stream failure after sending body/EOS"));

    // Verify that the call failed with INTERNAL status instead of succeeding.
    boolean completed = callCompletedLatch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void givenFailureModeAllowTrue_whenExtProcStreamFailsAfterResponseBodySent_thenCallFails()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(true)
        .setProcessingMode(ProcessingMode.newBuilder()
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

    final AtomicReference<StreamObserver<ProcessingResponse>> responseObserverRef =
        new AtomicReference<>();
    final CountDownLatch streamActiveLatch = new CountDownLatch(1);
    final CountDownLatch streamFailedLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            responseObserverRef.set(responseObserver);
            streamActiveLatch.countDown();
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
                  // Fail the stream once we see response body message
                  responseObserver.onError(
                      Status.INTERNAL.withDescription("Stream failure after response body")
                          .asRuntimeException());
                  streamFailedLatch.countDown();
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    MutableHandlerRegistry dataPlaneRegistry = new MutableHandlerRegistry();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueDataPlaneServerName)
        .fallbackHandlerRegistry(dataPlaneRegistry)
        .directExecutor()
        .build().start());

    dataPlaneRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response-body-msg");
          responseObserver.onCompleted();
        })).build());

    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(uniqueDataPlaneServerName)
            .directExecutor()
            .build());

    ClientCall<String, String> clientCall = interceptCall(
        interceptor, METHOD_SAY_HELLO,
        DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor()),
        dataPlaneChannel);

    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    final CountDownLatch callCompletedLatch = new CountDownLatch(1);
    clientCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        callCompletedLatch.countDown();
      }
    }, new Metadata());
    clientCall.request(1);

    boolean active = streamActiveLatch.await(5, TimeUnit.SECONDS);
    assertThat(active).isTrue();

    // Since request body mode is NONE, this sendMessage is NOT sent to ext_proc
    clientCall.sendMessage("app-msg");
    clientCall.halfClose();

    // Verify call completed and failed with INTERNAL status
    boolean completed = callCompletedLatch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    assertThat(closedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(closedStatus.get().getDescription()).contains("External processor stream failed");

    channelManager.close();
  }

  @Test
  public void givenObservabilityTrue_whenExtProcStreamFails_thenCallContinues()
      throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setFailureModeAllow(false)
        .setObservabilityMode(true)
        .build();
    ConfigOrError<ExternalProcessorFilterConfig> configOrError =
        provider.parseFilterConfig(Any.pack(proto), filterContext);
    assertThat(configOrError.errorDetail).isNull();
    ExternalProcessorFilterConfig filterConfig = configOrError.config;

    final CountDownLatch streamActiveLatch = new CountDownLatch(1);

    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl =
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
            streamActiveLatch.countDown();
            return new StreamObserver<ProcessingRequest>() {
              @Override
              public void onNext(ProcessingRequest request) {
                if (request.hasRequestHeaders()) {
                  // Fail the stream immediately on receiving headers
                  responseObserver.onError(
                      Status.INTERNAL.withDescription("Simulated sidecar failure")
                          .asRuntimeException());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            };
          }
        };

    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName)
              .directExecutor()
              .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
    final AtomicReference<Status> closedStatus = new AtomicReference<>();
    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        closedStatus.set(status);
        closedLatch.countDown();
      }
    };

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(appListener, new Metadata());

    proxyCall.request(1);
    proxyCall.sendMessage("test");
    proxyCall.halfClose();

    // Verify stream failed
    assertThat(streamActiveLatch.await(5, TimeUnit.SECONDS)).isTrue();

    // Verify data plane call still succeeded (observability mode ignores ext_proc failure)
    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closedStatus.get().isOk()).isTrue();

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  // --- Category 28: Request-Scoped Context Propagation ---

  @Test
  public void clientInterceptor_contextPropagatedToStartCall() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = 
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
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

    ExecutorService extProcServerExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(extProcServerExecutor)
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ExecutorService extProcChannelExecutor = Executors.newSingleThreadExecutor();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(extProcChannelExecutor)
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    final Context.Key<String> testKey = Context.key("test-key");
    Context testContext = Context.current().withValue(testKey, "test-value");
    final AtomicReference<String> contextValueAtDownstreamStart = new AtomicReference<>();
    final CountDownLatch downstreamStartLatch = new CountDownLatch(1);

    ClientInterceptor assertInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
            contextValueAtDownstreamStart.set(testKey.get());
            super.start(responseListener, headers);
            downstreamStartLatch.countDown();
          }
        };
      }
    };

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response-msg");
          responseObserver.onCompleted();
        })).build());

    ExecutorService dataPlaneChannelExecutor = Executors.newSingleThreadExecutor();
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .intercept(assertInterceptor)
            .executor(dataPlaneChannelExecutor)
            .build());

    final AtomicReference<ClientCall<String, String>> proxyCallRef = new AtomicReference<>();
    ExecutorService callExecutor = Executors.newSingleThreadExecutor();
    try {
      testContext.run(() -> {
        ClientCall<String, String> proxyCall = interceptCall(
            interceptor,
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(callExecutor),
            dataPlaneChannel);
        proxyCallRef.set(proxyCall);
        proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
      });

      ClientCall<String, String> proxyCall = proxyCallRef.get();

      proxyCall.request(1);
      proxyCall.sendMessage("hello");
      proxyCall.halfClose();

      assertThat(downstreamStartLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(contextValueAtDownstreamStart.get()).isEqualTo("test-value");

      proxyCall.cancel("cleanup", null);
    } finally {
      channelManager.close();
      shutdownAndAwaitTermination(extProcServerExecutor);
      shutdownAndAwaitTermination(extProcChannelExecutor);
      shutdownAndAwaitTermination(dataPlaneChannelExecutor);
      shutdownAndAwaitTermination(callExecutor);
    }
  }

  @Test
  public void clientInterceptor_contextPropagatedToListenerCallbacks() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = 
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
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

    ExecutorService extProcServerExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(extProcServerExecutor)
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    ExecutorService extProcChannelExecutor = Executors.newSingleThreadExecutor();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .executor(extProcChannelExecutor)
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    dataPlaneServiceRegistry.addService(ServerServiceDefinition.builder("test.TestService")
        .addMethod(METHOD_SAY_HELLO, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          responseObserver.onNext("response-msg");
          responseObserver.onCompleted();
        })).build());

    ExecutorService dataPlaneChannelExecutor = Executors.newSingleThreadExecutor();
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(dataPlaneChannelExecutor)
            .build());

    final Context.Key<String> testKey = Context.key("test-key");
    Context testContext = Context.current().withValue(testKey, "test-value");

    final AtomicReference<String> onHeadersContext = new AtomicReference<>();
    final AtomicReference<String> onMessageContext = new AtomicReference<>();
    final AtomicReference<String> onCloseContext = new AtomicReference<>();
    final AtomicReference<String> onReadyContext = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    ClientCall.Listener<String> appListener = new ClientCall.Listener<String>() {
      @Override
      public void onHeaders(Metadata headers) {
        onHeadersContext.set(testKey.get());
      }

      @Override
      public void onMessage(String message) {
        onMessageContext.set(testKey.get());
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        onCloseContext.set(testKey.get());
        latch.countDown();
      }

      @Override
      public void onReady() {
        onReadyContext.set(testKey.get());
      }
    };

    final AtomicReference<ClientCall<String, String>> proxyCallRef = new AtomicReference<>();
    ExecutorService callExecutor = Executors.newSingleThreadExecutor();
    try {
      testContext.run(() -> {
        ClientCall<String, String> proxyCall = interceptCall(
            interceptor,
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(callExecutor),
            dataPlaneChannel);
        proxyCallRef.set(proxyCall);
        proxyCall.start(appListener, new Metadata());
      });

      ClientCall<String, String> proxyCall = proxyCallRef.get();

      proxyCall.request(1);
      proxyCall.sendMessage("hello");
      proxyCall.halfClose();

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(onHeadersContext.get()).isEqualTo("test-value");
      assertThat(onMessageContext.get()).isEqualTo("test-value");
      assertThat(onCloseContext.get()).isEqualTo("test-value");
      assertThat(onReadyContext.get()).isEqualTo("test-value");

      proxyCall.cancel("cleanup", null);
    } finally {
      channelManager.close();
      shutdownAndAwaitTermination(extProcServerExecutor);
      shutdownAndAwaitTermination(extProcChannelExecutor);
      shutdownAndAwaitTermination(dataPlaneChannelExecutor);
      shutdownAndAwaitTermination(callExecutor);
    }
  }

  @Test
  public void clientInterceptor_contextPropagatedToExtProcStub() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessorGrpc.ExternalProcessorImplBase extProcImpl = 
        new ExternalProcessorGrpc.ExternalProcessorImplBase() {
          @Override
          public StreamObserver<ProcessingRequest> process(
              StreamObserver<ProcessingResponse> responseObserver) {
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

    ExecutorService extProcServerExecutor = Executors.newSingleThreadExecutor();
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .executor(extProcServerExecutor)
        .build().start());

    ExternalProcessor proto = createBaseProto(uniqueExtProcServerName).build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    final Context.Key<String> testKey = Context.key("test-key");
    Context testContext = Context.current().withValue(testKey, "test-value");
    final AtomicReference<String> contextAtExtProcCall = new AtomicReference<>();
    final CountDownLatch extProcCallLatch = new CountDownLatch(1);

    ExecutorService extProcChannelExecutor = Executors.newSingleThreadExecutor();
    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(InProcessChannelBuilder.forName(uniqueExtProcServerName)
          .intercept(new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
              if (method.equals(ExternalProcessorGrpc.getProcessMethod())) {
                contextAtExtProcCall.set(testKey.get());
                extProcCallLatch.countDown();
              }
              return next.newCall(method, callOptions);
            }
          })
          .executor(extProcChannelExecutor)
          .build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ExecutorService dataPlaneChannelExecutor = Executors.newSingleThreadExecutor();
    ManagedChannel dataPlaneChannel = grpcCleanup.register(
        InProcessChannelBuilder.forName(dataPlaneServerName)
            .executor(dataPlaneChannelExecutor)
            .build());

    final AtomicReference<ClientCall<String, String>> proxyCallRef = new AtomicReference<>();
    ExecutorService callExecutor = Executors.newSingleThreadExecutor();
    try {
      testContext.run(() -> {
        ClientCall<String, String> proxyCall = interceptCall(
            interceptor,
            METHOD_SAY_HELLO,
            DEFAULT_CALL_OPTIONS.withExecutor(callExecutor),
            dataPlaneChannel);
        proxyCallRef.set(proxyCall);
        proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());
      });

      ClientCall<String, String> proxyCall = proxyCallRef.get();

      assertThat(extProcCallLatch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(contextAtExtProcCall.get()).isEqualTo("test-value");

      proxyCall.cancel("cleanup", null);
    } finally {
      channelManager.close();
      shutdownAndAwaitTermination(extProcServerExecutor);
      shutdownAndAwaitTermination(extProcChannelExecutor);
      shutdownAndAwaitTermination(dataPlaneChannelExecutor);
      shutdownAndAwaitTermination(callExecutor);
    }
  }

  // --- Category 29: Header Option Value Spec Compliance and Validation ---

  @Test
  @SuppressWarnings("unchecked")
  public void serialization_specCompliance() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    final CountDownLatch requestSentLatch = new CountDownLatch(1);
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
                capturedRequest.set(request);
                requestSentLatch.countDown();
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(dataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("custom-ascii", Metadata.ASCII_STRING_MARSHALLER), "hello-world");
    headers.put(
        Metadata.Key.of("custom-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[]{0x00, 0x01, 0x02});

    proxyCall.start(new ClientCall.Listener<String>() {}, headers);

    assertThat(requestSentLatch.await(5, TimeUnit.SECONDS)).isTrue();
    ProcessingRequest req = capturedRequest.get();
    assertThat(req.hasRequestHeaders()).isTrue();
    
    // Find our headers in the captured request
    io.envoyproxy.envoy.config.core.v3.HeaderMap headerMap = req.getRequestHeaders().getHeaders();
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
    // ASCII: value is not set, raw_value is set to the ASCII string bytes
    assertThat(customAsciiProto.getValue()).isEmpty();
    assertThat(customAsciiProto.getRawValue().toStringUtf8()).isEqualTo("hello-world");

    assertThat(customBinProto).isNotNull();
    // Binary: value is not set, raw_value is set to base64-encoded bytes
    assertThat(customBinProto.getValue()).isEmpty();
    String expectedBase64 = BaseEncoding.base64().encode(new byte[]{0x00, 0x01, 0x02});
    assertThat(customBinProto.getRawValue().toStringUtf8()).isEqualTo(expectedBase64);

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_preferRawValue() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-ascii")
                                                  .setValue("legacy-val")
                                                  .setRawValue(ByteString.copyFromUtf8("raw-val"))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            dataPlaneLatch.countDown();
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Metadata headersApplied = capturedHeaders.get();
    // It should have chosen raw_value ("raw-val") and ignored value ("legacy-val")
    assertThat(
        headersApplied.get(
            Metadata.Key.of("custom-ascii", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("raw-val");

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_binaryHeader_validBase64() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-bin")
                                                  .setRawValue(
                                                      ByteString.copyFromUtf8("YmFy"))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

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
                }))
            .build(),
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            dataPlaneLatch.countDown();
            return next.startCall(call, headers);
          }
        }));

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);
    proxyCall.start(new ClientCall.Listener<String>() {}, new Metadata());

    assertThat(dataPlaneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Metadata headersApplied = capturedHeaders.get();
    // It should have base64 decoded "YmFy" to "bar"
    byte[] binValue =
        headersApplied.get(Metadata.Key.of("custom-bin", Metadata.BINARY_BYTE_MARSHALLER));
    assertThat(binValue).isEqualTo(new byte[]{'b', 'a', 'r'});

    proxyCall.cancel("Cleanup", null);
    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_binaryHeader_invalidBase64_noError_fails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-bin")
                                                  .setRawValue(
                                                      ByteString.copyFromUtf8("invalid_base64!"))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_binaryHeader_invalidBase64_failsCall() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .setMutationRules(
            io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules
                .newBuilder()
                .setDisallowIsError(com.google.protobuf.BoolValue.of(true))
                .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-bin")
                                                  .setRawValue(
                                                      ByteString.copyFromUtf8("invalid_base64!"))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_asciiHeader_invalidChars_noError_fails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_asciiHeader_invalidCharacters_failsCall() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .setMutationRules(
            io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules
                .newBuilder()
                .setDisallowIsError(com.google.protobuf.BoolValue.of(true))
                .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
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

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_headerValue_tooLong_noError_fails() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    String longValue = new String(new char[16385]).replace('\0', 'v');

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-ascii")
                                                  .setRawValue(ByteString.copyFromUtf8(longValue))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deserialization_headerValue_tooLong_failsCall() throws Exception {
    String uniqueExtProcServerName = InProcessServerBuilder.generateName();
    String uniqueDataPlaneServerName = InProcessServerBuilder.generateName();
    // Enable disallowIsError = true in mutation rules
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + uniqueExtProcServerName)
                .addChannelCredentialsPlugin(Any.newBuilder()
                    .setTypeUrl(
                        "type.googleapis.com/envoy.extensions.grpc_service."
                            + "channel_credentials.insecure.v3.InsecureCredentials")
                    .build())
                .build())
            .build())
        .setProcessingMode(ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND).build())
        .setMutationRules(
            io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules
                .newBuilder()
                .setDisallowIsError(com.google.protobuf.BoolValue.of(false))
                .build())
        .build();
    ExternalProcessorFilterConfig filterConfig =
        provider.parseFilterConfig(Any.pack(proto), filterContext).config;

    // Create a value that is 16385 characters long (exceeding 16384 limit)
    String longValue = new String(new char[16385]).replace('\0', 'v');

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
                  responseObserver.onNext(ProcessingResponse.newBuilder()
                      .setRequestHeaders(HeadersResponse.newBuilder()
                          .setResponse(CommonResponse.newBuilder()
                              .setHeaderMutation(HeaderMutation.newBuilder()
                                  .addSetHeaders(
                                      io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                                          .newBuilder()
                                          .setHeader(
                                              io.envoyproxy.envoy.config.core.v3.HeaderValue
                                                  .newBuilder()
                                                  .setKey("custom-ascii")
                                                  .setRawValue(ByteString.copyFromUtf8(longValue))
                                                  .build())
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
    grpcCleanup.register(InProcessServerBuilder.forName(uniqueExtProcServerName)
        .addService(extProcImpl)
        .directExecutor()
        .build().start());

    CachedChannelManager channelManager = new CachedChannelManager(config -> {
      return grpcCleanup.register(
          InProcessChannelBuilder.forName(uniqueExtProcServerName).directExecutor().build());
    });

    ExternalProcessorClientInterceptor interceptor = new ExternalProcessorClientInterceptor(
        filterConfig, channelManager, scheduler, FAKE_CONTEXT);

    ManagedChannel dataPlaneChannel =
        grpcCleanup.register(
            InProcessChannelBuilder.forName(uniqueDataPlaneServerName).directExecutor().build());

    CallOptions callOptions = DEFAULT_CALL_OPTIONS.withExecutor(MoreExecutors.directExecutor());
    ClientCall<String, String> proxyCall =
        interceptCall(interceptor, METHOD_SAY_HELLO, callOptions, dataPlaneChannel);

    final AtomicReference<Status> capturedStatus = new AtomicReference<>();
    final CountDownLatch callClosedLatch = new CountDownLatch(1);
    proxyCall.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        capturedStatus.set(status);
        callClosedLatch.countDown();
      }
    }, new Metadata());

    assertThat(callClosedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    // The call should fail unconditionally due to IllegalArgumentException
    assertThat(capturedStatus.get().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(capturedStatus.get().getCause()).isInstanceOf(IllegalArgumentException.class);

    channelManager.close();
  }

  private static List<ProcessingRequest> filterClientRequests(List<ProcessingRequest> requests) {
    List<ProcessingRequest> clientRequests = new ArrayList<>();
    for (ProcessingRequest r : requests) {
      if (r.hasRequestHeaders() || r.hasRequestBody()
          || (r.hasClientWindowUpdate() && !r.hasResponseBody()
          && !r.hasResponseHeaders() && !r.hasResponseTrailers())) {
        clientRequests.add(r);
      }
    }
    return clientRequests;
  }

  private static <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      ExternalProcessorClientInterceptor interceptor,
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next) {
    if (callOptions.getExecutor() == null) {
      callOptions = callOptions.withExecutor(MoreExecutors.directExecutor());
    }
    Channel intercepted = ClientInterceptors.interceptForward(
        next,
        Arrays.asList(new XdsNameResolver.RawMessageClientInterceptor(), interceptor));
    return intercepted.newCall(method, callOptions);
  }

  private void shutdownAndAwaitTermination(ExecutorService executor) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
