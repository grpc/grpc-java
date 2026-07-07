/*
 * Copyright 2025 The gRPC Authors
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

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.extauthz.CheckRequestBuilder;
import io.grpc.xds.internal.extauthz.CheckResponseHandler;
import io.grpc.xds.internal.extauthz.ExtAuthzClientCall;
import io.grpc.xds.internal.extauthz.ExtAuthzConfig;
import io.grpc.xds.internal.extauthz.ExtAuthzParseException;
import io.grpc.xds.internal.extauthz.FailingClientCall;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

// TODO(sauravz): Implement ext_authz metrics per gRFC A92 (Section: Metrics).
// Client-side counters: grpc.client_ext_authz.{allowed,denied,filter_disabled,failed}_rpcs
@ThreadSafe
final class ExtAuthzFilter implements Filter {

  private static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz";

  private static final String TYPE_URL_OVERRIDE_CONFIG =
      "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute";

  @Immutable
  static final class ExtAuthzFilterConfig implements Filter.FilterConfig {

    private final ExtAuthzConfig extAuthzConfig;

    ExtAuthzFilterConfig(ExtAuthzConfig extAuthzConfig) {
      this.extAuthzConfig = extAuthzConfig;
    }

    public ExtAuthzConfig extAuthzConfig() {
      return extAuthzConfig;
    }

    @Override
    public String typeUrl() {
      return ExtAuthzFilter.TYPE_URL;
    }

    public static ExtAuthzFilterConfig fromProto(
        ExtAuthz extAuthzProto,
        io.grpc.xds.client.Bootstrapper.BootstrapInfo bootstrapInfo,
        io.grpc.xds.client.Bootstrapper.ServerInfo serverInfo)
        throws ExtAuthzParseException {
      return new ExtAuthzFilterConfig(
          ExtAuthzConfigParser.parse(extAuthzProto, bootstrapInfo, serverInfo));
    }
  }

  // Placeholder for the external authorization filter's override config.
  @Immutable
  static final class ExtAuthzFilterConfigOverride implements Filter.FilterConfig {
    @Override
    public final String typeUrl() {
      return ExtAuthzFilter.TYPE_URL_OVERRIDE_CONFIG;
    }
  }

  @Immutable
  static final class Provider implements Filter.Provider {

    @Override
    public String[] typeUrls() {
      return new String[] {TYPE_URL, TYPE_URL_OVERRIDE_CONFIG};
    }

    @Override
    public boolean isClientFilter() {
      return GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_EXT_AUTHZ_ON_CLIENT", false);
    }

    @Override
    public ExtAuthzFilter newInstance(FilterContext context) {
      return new ExtAuthzFilter(new GrpcChannelProvider(), ThreadSafeRandomImpl.instance,
          HeaderMutator.create());
    }

    @Override
    public ConfigOrError<ExtAuthzFilterConfig> parseFilterConfig(
        Message rawProtoMessage, FilterConfigParseContext context) {
      ExtAuthz extAuthzProto;
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      Any anyMessage = (Any) rawProtoMessage;
      try {
        extAuthzProto = anyMessage.unpack(ExtAuthz.class);

        return ConfigOrError.fromConfig(
            ExtAuthzFilterConfig.fromProto(
                extAuthzProto, context.bootstrapInfo(), context.serverInfo()));
      } catch (InvalidProtocolBufferException | ExtAuthzParseException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
    }

    @Override
    public ConfigOrError<ExtAuthzFilterConfigOverride> parseFilterConfigOverride(
        Message rawProtoMessage, FilterConfigParseContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      return ConfigOrError.fromConfig(new ExtAuthzFilterConfigOverride());
    }
  }

  /**
   * Provides {@link ManagedChannel} instances for a given {@link GrpcServiceConfig}
   * and manages their lifecycle.
   */
  interface ChannelProvider extends java.io.Closeable {
    /** Creates or returns a ManagedChannel for the given config. */
    ManagedChannel getChannel(GrpcServiceConfig config);

    /** Shuts down any channels held by this provider. */
    @Override
    void close();
  }

  // TODO(sauravz): Harden channel lifecycle management before launch.
  // This implementation is intentionally simple and synchronized.
  // Consider implementing proper keyed caching (by target + credentials)
  // and graceful shutdown with awaitTermination.
  private static final class GrpcChannelProvider implements ChannelProvider {
    private final Object lock = new Object();
    private ManagedChannel channel;

    @Override
    public ManagedChannel getChannel(GrpcServiceConfig config) {
      synchronized (lock) {
        if (channel != null && !channel.isShutdown()) {
          return channel;
        }
        if (channel != null) {
          channel.shutdown();
        }
        GrpcServiceConfig.GoogleGrpcConfig googleGrpc = config.googleGrpc();
        channel = Grpc.newChannelBuilder(
            googleGrpc.target(),
            googleGrpc.configuredChannelCredentials()
                .channelCredentials()).build();
        return channel;
      }
    }

    @Override
    public void close() {
      synchronized (lock) {
        if (channel != null) {
          channel.shutdown();
          channel = null;
        }
      }
    }
  }

  private final ChannelProvider channelProvider;
  private final ThreadSafeRandom random;
  private final HeaderMutator headerMutator;


  ExtAuthzFilter(ChannelProvider channelProvider, ThreadSafeRandom random,
      HeaderMutator headerMutator) {
    this.channelProvider = channelProvider;
    this.random = random;
    this.headerMutator = headerMutator;
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig config,
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    if (overrideConfig != null) {
      return null;
    }
    if (!(config instanceof ExtAuthzFilterConfig)) {
      return null;
    }
    ExtAuthzFilterConfig extAuthzFilterConfig = (ExtAuthzFilterConfig) config;
    ExtAuthzConfig extAuthzConfig = extAuthzFilterConfig.extAuthzConfig();
    AuthorizationGrpc.AuthorizationStub stub = AuthorizationGrpc.newStub(
        channelProvider.getChannel(extAuthzConfig.grpcService()));
    if (extAuthzConfig.grpcService().googleGrpc().callCredentials().isPresent()) {
      stub = stub.withCallCredentials(
          extAuthzConfig.grpcService().googleGrpc().callCredentials().get());
    }
    if (extAuthzConfig.grpcService().timeout().isPresent()) {
      stub = stub.withDeadlineAfter(
          extAuthzConfig.grpcService().timeout().get().toMillis(),
          TimeUnit.MILLISECONDS);
    }
    return new ExtAuthzClientInterceptor(extAuthzConfig, stub, random,
        new CheckRequestBuilder(extAuthzConfig),
        new CheckResponseHandler(
            new HeaderMutationFilter(extAuthzConfig.decoderHeaderMutationRules())),
        headerMutator,
        scheduler);
  }

  @Override
  public void close() {
    channelProvider.close();
  }

  /**
   * A client interceptor that performs external authorization for outgoing RPCs.
   */
  @ThreadSafe
  static final class ExtAuthzClientInterceptor implements ClientInterceptor {

    private final ExtAuthzConfig config;
    private final AuthorizationGrpc.AuthorizationStub authzStub;
    private final ThreadSafeRandom random;
    private final CheckRequestBuilder checkRequestBuilder;
    private final CheckResponseHandler responseHandler;
    private final HeaderMutator headerMutator;
    private final ScheduledExecutorService scheduler;

    ExtAuthzClientInterceptor(
        ExtAuthzConfig config,
        AuthorizationGrpc.AuthorizationStub authzStub,
        ThreadSafeRandom random,
        CheckRequestBuilder checkRequestBuilder,
        CheckResponseHandler responseHandler,
        HeaderMutator headerMutator,
        ScheduledExecutorService scheduler) {
      this.config = config;
      this.random = random;
      this.authzStub = authzStub;
      this.checkRequestBuilder = checkRequestBuilder;
      this.responseHandler = responseHandler;
      this.headerMutator = headerMutator;
      this.scheduler = scheduler;
    }

    @com.google.common.annotations.VisibleForTesting
    AuthorizationGrpc.AuthorizationStub getAuthzStubForTest() {
      return authzStub;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      FractionMatcher filterEnabled = config.filterEnabled();
      
      // 1. FractionMatcher Evaluation (Spec Parity / Correctness fix)
      if (random.nextInt(filterEnabled.denominator()) >= filterEnabled.numerator()) {
        if (config.denyAtDisable()) {
          return new FailingClientCall<>(config.statusOnError());
        }
        return next.newCall(method, callOptions);
      }

      // 2. Strict Fail-Fast Executor Validation
      Executor executor = callOptions.getExecutor();
      if (executor == null) {
        return new FailingClientCall<>(Status.INTERNAL
            .withDescription("No executor provided in CallOptions or Channel"));
      }

      // 3. Return concrete subclassed DelayedClientCall
      return new ExtAuthzClientCall<>(
          executor,
          scheduler,
          callOptions,
          next,
          method,
          authzStub,
          checkRequestBuilder,
          responseHandler,
          headerMutator,
          config);
    }
  }
}
