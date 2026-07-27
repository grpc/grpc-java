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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.grpc.ClientInterceptor;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.internal.HeaderForwardingRulesConfig;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParseException;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Filter for external processing as per gRFC A93.
 */
public class ExternalProcessorFilter implements Filter {
  static final String TYPE_URL = 
      "type.googleapis.com/envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor";

  private final CachedChannelManager cachedChannelManager;
  private final FilterContext context;

  public ExternalProcessorFilter(FilterContext context) {
    this(context, new CachedChannelManager());
  }

  @VisibleForTesting
  ExternalProcessorFilter(FilterContext context, CachedChannelManager cachedChannelManager) {
    this.context = checkNotNull(context, "context");
    this.cachedChannelManager = checkNotNull(cachedChannelManager, "cachedChannelManager");
  }

  @Override
  public void close() {
    cachedChannelManager.close();
  }

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", false);
    }

    @Override
    public ExternalProcessorFilter newInstance(FilterContext context) {
      return new ExternalProcessorFilter(context);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterConfig> parseFilterConfig(
        Message rawProtoMessage, FilterConfigParseContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExternalProcessor externalProcessor;
      try {
        externalProcessor = ((Any) rawProtoMessage).unpack(ExternalProcessor.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }

      return ExternalProcessorFilterConfig.create(externalProcessor, context);
    }

    @Override
    public ConfigOrError<ExternalProcessorFilterOverrideConfig> parseFilterConfigOverride(
        Message rawProtoMessage, FilterConfigParseContext context) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      ExtProcPerRoute perRoute;
      try {
        perRoute = ((Any) rawProtoMessage).unpack(ExtProcPerRoute.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
      ExtProcOverrides overrides = perRoute.hasOverrides()
          ? perRoute.getOverrides() : ExtProcOverrides.getDefaultInstance();
      return ExternalProcessorFilterOverrideConfig.create(overrides, context);
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig filterConfig,
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    ExternalProcessorFilterConfig extProcFilterConfig =
        (ExternalProcessorFilterConfig) filterConfig;
    if (overrideConfig != null) {
      extProcFilterConfig = mergeConfigs(extProcFilterConfig,
          (ExternalProcessorFilterOverrideConfig) overrideConfig);
    }
    return new ExternalProcessorClientInterceptor(
        extProcFilterConfig, cachedChannelManager, scheduler, context);
  }

  private static ExternalProcessorFilterConfig mergeConfigs(
      ExternalProcessorFilterConfig extProcFilterConfig,
      ExternalProcessorFilterOverrideConfig extProcFilterConfigOverride) {
    ExternalProcessor parentProto = extProcFilterConfig.getExternalProcessor();
    ExternalProcessor.Builder mergedProtoBuilder = parentProto.toBuilder();

    if (extProcFilterConfigOverride.hasProcessingMode()) {
      mergedProtoBuilder.setProcessingMode(extProcFilterConfigOverride.getProcessingMode());
    }

    if (extProcFilterConfigOverride.hasRequestAttributes()) {
      mergedProtoBuilder.clearRequestAttributes()
          .addAllRequestAttributes(extProcFilterConfigOverride.getRequestAttributesList());
    }
    if (extProcFilterConfigOverride.hasResponseAttributes()) {
      mergedProtoBuilder.clearResponseAttributes()
          .addAllResponseAttributes(extProcFilterConfigOverride.getResponseAttributesList());
    }
    if (extProcFilterConfigOverride.hasGrpcService()) {
      mergedProtoBuilder.setGrpcService(extProcFilterConfigOverride.getGrpcService());
    }

    if (extProcFilterConfigOverride.hasFailureModeAllow()) {
      mergedProtoBuilder.setFailureModeAllow(extProcFilterConfigOverride.getFailureModeAllow());
    }

    GrpcServiceConfig grpcServiceConfig =
        extProcFilterConfigOverride.getGrpcServiceConfig() != null
            ? extProcFilterConfigOverride.getGrpcServiceConfig()
            : extProcFilterConfig.getGrpcServiceConfig();

    return new ExternalProcessorFilterConfig(
        mergedProtoBuilder.build(),
        grpcServiceConfig,
        extProcFilterConfig.getMutationRulesConfig(),
        extProcFilterConfig.getForwardRulesConfig());
  }

  private static ConfigOrError<Optional<GrpcServiceConfig>> parseAndValidate(
      ProcessingMode mode,
      GrpcService grpcService,
      boolean isParent,
      FilterConfigParseContext context) {
    if (mode.getRequestBodyMode() != ProcessingMode.BodySendMode.GRPC
        && mode.getRequestBodyMode() != ProcessingMode.BodySendMode.NONE) {
      return ConfigOrError.fromError("Invalid request_body_mode: " + mode.getRequestBodyMode()
          + ". Only GRPC and NONE are supported.");
    }
    if (mode.getResponseBodyMode() != ProcessingMode.BodySendMode.GRPC
        && mode.getResponseBodyMode() != ProcessingMode.BodySendMode.NONE) {
      return ConfigOrError.fromError("Invalid response_body_mode: " + mode.getResponseBodyMode()
          + ". Only GRPC and NONE are supported.");
    }

    if (mode.getResponseBodyMode() == ProcessingMode.BodySendMode.GRPC
        && mode.getResponseTrailerMode() != ProcessingMode.HeaderSendMode.SEND) {
      return ConfigOrError.fromError(
          "Invalid response_trailer_mode: " + mode.getResponseTrailerMode()
              + ". response_trailer_mode must be SEND if response_body_mode is GRPC.");
    }

    try {
      if (grpcService != null && grpcService.hasGoogleGrpc()) {
        GrpcServiceConfig grpcServiceConfig = GrpcServiceConfigParser.parse(
            grpcService, context.bootstrapInfo(), context.serverInfo());
        return ConfigOrError.fromConfig(Optional.of(grpcServiceConfig));
      } else if (isParent) {
        return ConfigOrError.fromError("Error parsing GrpcService config: " 
            + "Unsupported: GrpcService must have GoogleGrpc, got: " + grpcService);
      }
      return ConfigOrError.fromConfig(Optional.empty());
    } catch (GrpcServiceParseException e) {
      return ConfigOrError.fromError("Error parsing GrpcService config: " + e.getMessage());
    }
  }

  static final class ExternalProcessorFilterConfig implements FilterConfig {

    private final ExternalProcessor externalProcessor;
    private final GrpcServiceConfig grpcServiceConfig;
    private final Optional<HeaderMutationRulesConfig> mutationRulesConfig;
    private final Optional<HeaderForwardingRulesConfig> forwardRulesConfig;

    static ConfigOrError<ExternalProcessorFilterConfig> create(
        ExternalProcessor externalProcessor, FilterConfigParseContext context) {
      ProcessingMode mode = externalProcessor.getProcessingMode();
      GrpcService grpcService = externalProcessor.getGrpcService();
      HeaderMutationRulesConfig mutationRulesConfig = null;
      HeaderForwardingRulesConfig forwardRulesConfig = null;

      if (externalProcessor.hasMutationRules()) {
        try {
          mutationRulesConfig = 
              HeaderMutationRulesParser.parse(externalProcessor.getMutationRules());
        } catch (HeaderMutationRulesParseException e) {
          return ConfigOrError.fromError("Error parsing HeaderMutationRules: " + e.getMessage());
        }
      }

      if (externalProcessor.hasForwardRules()) {
        forwardRulesConfig = 
            HeaderForwardingRulesConfig.create(externalProcessor.getForwardRules());
      }

      if (externalProcessor.hasDeferredCloseTimeout()) {
        Duration deferredCloseTimeout = externalProcessor.getDeferredCloseTimeout();
        try {
          Durations.checkValid(deferredCloseTimeout);
        } catch (IllegalArgumentException e) {
          return ConfigOrError.fromError("Invalid deferred_close_timeout: " + e.getMessage());
        }
        long deferredCloseTimeoutNanos = Durations.toNanos(deferredCloseTimeout);
        if (deferredCloseTimeoutNanos <= 0) {
          return ConfigOrError.fromError("deferred_close_timeout must be positive");
        }
      }

      ConfigOrError<Optional<GrpcServiceConfig>> parsed =
          parseAndValidate(mode, grpcService, true, context);
      if (parsed.errorDetail != null) {
        return ConfigOrError.fromError(parsed.errorDetail);
      }

      return ConfigOrError.fromConfig(new ExternalProcessorFilterConfig(
          externalProcessor, parsed.config.orElse(null), 
          Optional.ofNullable(mutationRulesConfig), 
          Optional.ofNullable(forwardRulesConfig)));
    }

    ExternalProcessorFilterConfig(
        ExternalProcessor externalProcessor,
        GrpcServiceConfig grpcServiceConfig,
        Optional<HeaderMutationRulesConfig> mutationRulesConfig,
        Optional<HeaderForwardingRulesConfig> forwardRulesConfig) {
      this.externalProcessor = checkNotNull(externalProcessor, "externalProcessor");
      this.grpcServiceConfig = grpcServiceConfig;
      this.mutationRulesConfig = mutationRulesConfig;
      this.forwardRulesConfig = forwardRulesConfig;
    }

    @Override
    public String typeUrl() {
      return TYPE_URL;
    }

    ExternalProcessor getExternalProcessor() {
      return externalProcessor;
    }

    GrpcServiceConfig getGrpcServiceConfig() {
      return grpcServiceConfig;
    }

    Optional<HeaderMutationRulesConfig> getMutationRulesConfig() {
      return mutationRulesConfig;
    }

    Optional<HeaderForwardingRulesConfig> getForwardRulesConfig() {
      return forwardRulesConfig;
    }

    ImmutableList<String> getRequestAttributes() {
      return ImmutableList.copyOf(externalProcessor.getRequestAttributesList());
    }

    boolean getDisableImmediateResponse() {
      return externalProcessor.getDisableImmediateResponse();
    }

    long getDeferredCloseTimeoutNanos() {
      if (externalProcessor.hasDeferredCloseTimeout()) {
        return Durations.toNanos(externalProcessor.getDeferredCloseTimeout());
      }
      return TimeUnit.SECONDS.toNanos(5);
    }

    boolean getObservabilityMode() {
      return externalProcessor.getObservabilityMode();
    }

    boolean getFailureModeAllow() {
      return externalProcessor.getFailureModeAllow();
    }
  }

  static final class ExternalProcessorFilterOverrideConfig implements FilterConfig {
    private final ExtProcOverrides extProcOverrides;
    private final GrpcServiceConfig grpcServiceConfig;

    static ConfigOrError<ExternalProcessorFilterOverrideConfig> create(
        ExtProcOverrides overrides, FilterConfigParseContext context) {
      ConfigOrError<Optional<GrpcServiceConfig>> parsed =
          parseAndValidate(
              overrides.getProcessingMode(), overrides.getGrpcService(), false, context);
      if (parsed.errorDetail != null) {
        return ConfigOrError.fromError(parsed.errorDetail);
      }
      return ConfigOrError.fromConfig(
          new ExternalProcessorFilterOverrideConfig(overrides, parsed.config.orElse(null)));
    }

    ExternalProcessorFilterOverrideConfig(
        ExtProcOverrides extProcOverrides, GrpcServiceConfig grpcServiceConfig) {
      this.extProcOverrides = checkNotNull(extProcOverrides, "extProcOverrides");
      this.grpcServiceConfig = grpcServiceConfig;
    }

    @Override
    public String typeUrl() {
      return TYPE_URL;
    }

    boolean hasProcessingMode() {
      return extProcOverrides.hasProcessingMode();
    }

    ProcessingMode getProcessingMode() {
      return extProcOverrides.getProcessingMode();
    }

    boolean hasRequestAttributes() {
      return extProcOverrides.getRequestAttributesCount() > 0;
    }

    List<String> getRequestAttributesList() {
      return extProcOverrides.getRequestAttributesList();
    }

    boolean hasResponseAttributes() {
      return extProcOverrides.getResponseAttributesCount() > 0;
    }

    List<String> getResponseAttributesList() {
      return extProcOverrides.getResponseAttributesList();
    }

    boolean hasGrpcService() {
      return extProcOverrides.hasGrpcService();
    }

    GrpcService getGrpcService() {
      return extProcOverrides.getGrpcService();
    }

    boolean hasFailureModeAllow() {
      return extProcOverrides.hasFailureModeAllow();
    }

    boolean getFailureModeAllow() {
      return extProcOverrides.hasFailureModeAllow()
          && extProcOverrides.getFailureModeAllow().getValue();
    }

    GrpcServiceConfig getGrpcServiceConfig() {
      return grpcServiceConfig;
    }
  }


}
