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

import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterOverrideConfig;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData.Node;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ExternalProcessorFilter} configuration parsing and provider.
 */
@RunWith(JUnit4.class)
public class ExternalProcessorFilterTest {
  static {
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", "true");
  }

  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private String extProcServerName;
  private ExternalProcessorFilter.Provider provider;
  private Filter.FilterConfigParseContext filterContext;
  private Bootstrapper.BootstrapInfo bootstrapInfo;
  private Bootstrapper.ServerInfo serverInfo;

  @Before
  public void setUp() throws Exception {
    NameResolverRegistry.getDefaultRegistry().register(new InProcessNameResolverProvider());
    extProcServerName = "test-ext-proc-server";
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

  // --- Category 1: Filter Provider registration based on flag ---
  @Test
  public void provider_registeredInFilterRegistry_basedOnFlag() {
    // Test with flag true
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", "true");
    try {
      FilterRegistry.reset();
      FilterRegistry registry = FilterRegistry.getDefaultRegistry();
      assertThat(registry.get(ExternalProcessorFilter.TYPE_URL)).isNotNull();
      assertThat(registry.get(ExternalProcessorFilter.TYPE_URL).isClientFilter()).isTrue();
    } finally {
      System.clearProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT");
    }

    // Test with flag false
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", "false");
    try {
      FilterRegistry.reset();
      FilterRegistry registry = FilterRegistry.getDefaultRegistry();
      assertThat(registry.get(ExternalProcessorFilter.TYPE_URL)).isNotNull();
      assertThat(registry.get(ExternalProcessorFilter.TYPE_URL).isClientFilter()).isFalse();
    } finally {
      System.clearProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT");
    }

    // Restore default for other tests
    System.setProperty("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", "true");
    FilterRegistry.reset();
    FilterRegistry.getDefaultRegistry();
  }

  // --- Category 2: Configuration Parsing & Provider ---

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
  public void givenUnsupportedRequestBodyMode_whenParsed_thenReturnsError() throws Exception {
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
  public void givenUnsupportedResponseBodyMode_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED) // Unsupported
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Invalid response_body_mode");
  }

  @Test
  public void givenNonGoogleGrpcService_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder().build()) // Invalid: no GoogleGrpc
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("GrpcService must have GoogleGrpc");
  }

  @Test
  public void givenInvalidGrpcService_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = ExternalProcessor.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("in-process:///" + extProcServerName)
                // Invalid: no channel credentials plugin
                .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Error parsing GrpcService config");
    assertThat(result.errorDetail).contains("No valid supported channel_credentials found");
  }

  @Test
  public void givenInvalidMutationRules_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setMutationRules(
            io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules.newBuilder()
                .setAllowExpression(io.envoyproxy.envoy.type.matcher.v3.RegexMatcher.newBuilder()
                    .setRegex("allow-[") // Invalid regex pattern
                    .build())
                .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Error parsing HeaderMutationRules");
    assertThat(result.errorDetail).contains("Invalid regex pattern for allow_expression");
  }

  @Test
  public void givenInvalidDeferredCloseTimeout_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(
            com.google.protobuf.Duration.newBuilder().setSeconds(315576000001L).build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("Invalid deferred_close_timeout");
  }

  @Test
  public void givenNonPositiveDeferredCloseTimeout_whenParsed_thenReturnsError() throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setDeferredCloseTimeout(
            com.google.protobuf.Duration.newBuilder().setSeconds(0).setNanos(0).build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains("deferred_close_timeout must be positive");
  }

  @Test
  public void givenResponseBodyModeGrpcWithResponseTrailerModeNotSend_whenParsed_thenReturnsError()
      throws Exception {
    ExternalProcessor proto = createBaseProto(extProcServerName)
        .setProcessingMode(ProcessingMode.newBuilder()
            .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(proto), filterContext);

    assertThat(result.errorDetail).contains(
        "response_trailer_mode must be SEND if response_body_mode is GRPC");
  }

  @Test
  public void givenOverrideConfigWithBodyGrpcAndTrailerNotSend_whenParsed_thenError()
      throws Exception {
    ExtProcPerRoute perRoute = ExtProcPerRoute.newBuilder()
        .setOverrides(ExtProcOverrides.newBuilder()
            .setProcessingMode(ProcessingMode.newBuilder()
                .setResponseBodyMode(ProcessingMode.BodySendMode.GRPC)
                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .build())
            .build())
        .build();

    ConfigOrError<ExternalProcessorFilterOverrideConfig> result =
        provider.parseFilterConfigOverride(Any.pack(perRoute), filterContext);

    assertThat(result.errorDetail).contains(
        "response_trailer_mode must be SEND if response_body_mode is GRPC");
  }

  @Test
  public void givenInvalidProto_whenParseFilterConfig_thenReturnsError() throws Exception {
    com.google.protobuf.Struct structMessage = com.google.protobuf.Struct.newBuilder().build();
    ConfigOrError<ExternalProcessorFilterConfig> result =
        provider.parseFilterConfig(Any.pack(structMessage), filterContext);

    assertThat(result.errorDetail).contains("Invalid proto:");
  }

  @Test
  public void givenInvalidProto_whenParseFilterConfigOverride_thenReturnsError() throws Exception {
    com.google.protobuf.Struct structMessage = com.google.protobuf.Struct.newBuilder().build();
    ConfigOrError<ExternalProcessorFilterOverrideConfig> result =
        provider.parseFilterConfigOverride(Any.pack(structMessage), filterContext);

    assertThat(result.errorDetail).contains("Invalid proto:");
  }
}
