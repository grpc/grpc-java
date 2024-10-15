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
import static io.grpc.xds.client.XdsResourceType.ResourceInvalidException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaBucketSettings;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import io.grpc.xds.internal.datatype.GrpcService;
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.matchers.Matcher;
import io.grpc.xds.internal.matchers.MatcherList;
import io.grpc.xds.internal.matchers.OnMatch;
import io.grpc.xds.internal.rlqs.RlqsBucketSettings;
import io.grpc.xds.internal.rlqs.RlqsCache;
import io.grpc.xds.internal.rlqs.RlqsEngine;
import io.grpc.xds.internal.rlqs.RlqsRateLimitResult;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** RBAC Http filter implementation. */
// TODO(sergiitk): introduce a layer between the filter and interceptor.
// lds has filter names and the names are unique - even for server instances.
final class RlqsFilter implements Filter, ServerInterceptorBuilder {
  static final boolean enabled = GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_ENABLE_RLQS", false);

  // TODO(sergiitk): [IMPL] remove
  // Do do not fail on parsing errors, only log requests.
  static final boolean dryRun = GrpcUtil.getFlag("GRPC_EXPERIMENTAL_RLQS_DRY_RUN", false);

  static final RlqsFilter INSTANCE = new RlqsFilter();

  static final String TYPE_URL = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig";
  static final String TYPE_URL_OVERRIDE_CONFIG = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride";

  private final AtomicReference<RlqsCache> rlqsCache = new AtomicReference<>();

  private final InternalLogId logId;
  private final XdsLogger logger;

  public RlqsFilter() {
    logId = InternalLogId.allocate("rlqs-filter", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created RLQS Filter with logId=" + logId);
  }

  @Override
  public String[] typeUrls() {
    return new String[]{TYPE_URL, TYPE_URL_OVERRIDE_CONFIG};
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public ConfigOrError<RlqsFilterConfig> parseFilterConfig(Message rawProtoMessage) {
    try {
      RlqsFilterConfig rlqsFilterConfig =
          parseRlqsFilter(unpackAny(rawProtoMessage, RateLimitQuotaFilterConfig.class));
      return ConfigOrError.fromConfig(rlqsFilterConfig);
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Can't unpack RateLimitQuotaFilterConfig proto: " + e);
    } catch (ResourceInvalidException e) {
      return ConfigOrError.fromError(e.getMessage());
    }
  }

  @Override
  public ConfigOrError<RlqsFilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    try {
      RlqsFilterConfig rlqsFilterConfig =
          parseRlqsFilterOverride(unpackAny(rawProtoMessage, RateLimitQuotaOverride.class));
      return ConfigOrError.fromConfig(rlqsFilterConfig);
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Can't unpack RateLimitQuotaOverride proto: " + e);
    } catch (ResourceInvalidException e) {
      return ConfigOrError.fromError(e.getMessage());
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig) {
    throw new UnsupportedOperationException("ScheduledExecutorService scheduler required");
  }

  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config,
      @Nullable FilterConfig overrideConfig,
      ScheduledExecutorService scheduler) {
    // Called when we get an xds update - when the LRS or RLS changes.
    RlqsFilterConfig rlqsFilterConfig = (RlqsFilterConfig) checkNotNull(config, "config");

    // Per-route and per-host configuration overrides.
    if (overrideConfig != null) {
      RlqsFilterConfig rlqsFilterOverride = (RlqsFilterConfig) overrideConfig;
      // All fields are inherited from the main config, unless overridden.
      RlqsFilterConfig.Builder overrideBuilder = rlqsFilterConfig.toBuilder();
      if (!rlqsFilterOverride.domain().isEmpty()) {
        overrideBuilder.domain(rlqsFilterOverride.domain());
      }
      if (rlqsFilterOverride.bucketMatchers() != null) {
        overrideBuilder.bucketMatchers(rlqsFilterOverride.bucketMatchers());
      }
      // Override bucket matchers if not null.
      rlqsFilterConfig = overrideBuilder.build();
    }

    rlqsCache.compareAndSet(null, RlqsCache.newInstance(scheduler));
    return generateRlqsInterceptor(rlqsFilterConfig);
  }

  @Override
  public void shutdown() {
    // TODO(sergiitk): [DESIGN] besides shutting down everything, should there
    //    be per-route interceptor destructors?
    RlqsCache oldCache = rlqsCache.getAndUpdate(unused -> null);
    if (oldCache != null) {
      oldCache.shutdown();
    }
  }

  @Nullable
  private ServerInterceptor generateRlqsInterceptor(RlqsFilterConfig config) {
    checkNotNull(config, "config");
    checkNotNull(config.rlqsService(), "config.rlqsService");
    RlqsCache rlqsCache = this.rlqsCache.get();
    if (rlqsCache == null) {
      // Being shut down, return no interceptor.
      return null;
    }

    final RlqsEngine rlqsEngine = rlqsCache.getOrCreateRlqsEngine(config);

    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        HttpMatchInput httpMatchInput = HttpMatchInput.create(headers, call);

        // TODO(sergiitk): [IMPL] Remove
        if (dryRun) {
          logger.log(XdsLogLevel.INFO, "RLQS DRY RUN: request <<" + httpMatchInput + ">>");
          return next.startCall(call, headers);
        }

        RlqsRateLimitResult result = rlqsEngine.rateLimit(httpMatchInput);
        if (result.isAllowed()) {
          return next.startCall(call, headers);
        }
        RlqsRateLimitResult.DenyResponse denyResponse = result.denyResponse().get();
        call.close(denyResponse.status(), denyResponse.headersToAdd());
        return new ServerCall.Listener<ReqT>(){};
      }
    };
  }

  @VisibleForTesting
  RlqsFilterConfig parseRlqsFilter(RateLimitQuotaFilterConfig rlqsFilterProto)
      throws ResourceInvalidException, InvalidProtocolBufferException {
    RlqsFilterConfig.Builder builder = RlqsFilterConfig.builder();
    if (rlqsFilterProto.getDomain().isEmpty()) {
      throw new ResourceInvalidException("RateLimitQuotaFilterConfig domain is required");
    }
    builder.domain(rlqsFilterProto.getDomain())
        .rlqsService(GrpcService.fromEnvoyProto(rlqsFilterProto.getRlqsServer()));

    // TODO(sergiitk): [IMPL] Remove
    if (dryRun) {
      logger.log(XdsLogLevel.INFO, "RLQS DRY RUN: skipping matchers");
      return builder.build();
    }

    // TODO(sergiitk): [IMPL] actually parse, move to RlqsBucketSettings.fromProto()
    RateLimitQuotaBucketSettings fallbackBucketSettingsProto = unpackAny(
        rlqsFilterProto.getBucketMatchers().getOnNoMatch().getAction().getTypedConfig(),
        RateLimitQuotaBucketSettings.class);
    RlqsBucketSettings fallbackBucket = RlqsBucketSettings.create(
        ImmutableMap.of("bucket_id", headers -> "hello"),
        fallbackBucketSettingsProto.getReportingInterval());

    // TODO(sergiitk): [IMPL] actually parse, move to Matcher.fromProto()
    Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers = new RlqsMatcher(fallbackBucket);

    return builder.bucketMatchers(bucketMatchers).build();
  }

  static class RlqsMatcher extends Matcher<HttpMatchInput, RlqsBucketSettings> {
    private final RlqsBucketSettings fallbackBucket;

    RlqsMatcher(RlqsBucketSettings fallbackBucket) {
      this.fallbackBucket = fallbackBucket;
    }

    @Nullable
    @Override
    public MatcherList<HttpMatchInput, RlqsBucketSettings> matcherList() {
      return null;
    }

    @Override
    public OnMatch<HttpMatchInput, RlqsBucketSettings> onNoMatch() {
      return OnMatch.ofAction(fallbackBucket);
    }

    @Override
    public RlqsBucketSettings match(HttpMatchInput input) {
      return null;
    }
  }

  @VisibleForTesting
  static RlqsFilterConfig parseRlqsFilterOverride(RateLimitQuotaOverride rlqsFilterProtoOverride)
      throws ResourceInvalidException {
    RlqsFilterConfig.Builder builder = RlqsFilterConfig.builder();
    // TODO(sergiitk): [IMPL] bucket_matchers.

    return builder.domain(rlqsFilterProtoOverride.getDomain()).build();
  }

  private static <T extends com.google.protobuf.Message> T unpackAny(
      Message message, Class<T> clazz) throws InvalidProtocolBufferException {
    if (!(message instanceof Any)) {
      throw new InvalidProtocolBufferException(
          "Invalid config type: " + message.getClass().getCanonicalName());
    }
    return ((Any) message).unpack(clazz);
  }
}
