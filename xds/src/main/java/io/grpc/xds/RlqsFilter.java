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
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.internal.datatype.GrpcService;
import io.grpc.xds.internal.rlqs.RlqsClientPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** RBAC Http filter implementation. */
final class RlqsFilter implements Filter, ServerInterceptorBuilder {
  // private static final Logger logger = Logger.getLogger(RlqsFilter.class.getName());

  static final RlqsFilter INSTANCE = new RlqsFilter();

  static final String TYPE_URL = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig";
  static final String TYPE_URL_OVERRIDE_CONFIG = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride";

  private final AtomicReference<RlqsClientPool> rlqsClientPoolRef = new AtomicReference<>();

  // RlqsFilter() {
  //   rlqsClientPool = new RlqsClientPool()
  // }

  @Override
  public String[] typeUrls() {
    return new String[]{TYPE_URL, TYPE_URL_OVERRIDE_CONFIG};
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
    // called when we get an xds update - when the LRS or RLS changes.
    // TODO(sergiitk): this needs to be confirmed.
    RlqsFilterConfig rlqsFilterConfig = (RlqsFilterConfig) checkNotNull(config, "config");

    // Per-route and per-host configuration overrides.
    if (overrideConfig != null) {
      RlqsFilterConfig rlqsFilterOverride = (RlqsFilterConfig) overrideConfig;
      // All fields are inherited from the main config, unless overridden.
      RlqsFilterConfig.Builder overrideBuilder = rlqsFilterConfig.toBuilder();
      if (!rlqsFilterOverride.domain().isEmpty()) {
        overrideBuilder.domain(rlqsFilterOverride.domain());
      }
      // Override bucket matchers if not null.
      rlqsFilterConfig = overrideBuilder.build();
    }

    rlqsClientPoolRef.compareAndSet(null, RlqsClientPool.newInstance(scheduler));
    return generateRlqsInterceptor(rlqsFilterConfig);
  }

  @Override
  public void shutdown() {
    // TODO(sergiitk): besides shutting down everything, should there be a per-route destructor?
    RlqsClientPool oldClientPool = rlqsClientPoolRef.getAndUpdate(unused -> null);
    if (oldClientPool != null) {
      oldClientPool.shutdown();
    }
  }

  @Nullable
  private ServerInterceptor generateRlqsInterceptor(RlqsFilterConfig config) {
    checkNotNull(config, "config");
    checkNotNull(config.rlqsService(), "config.rlqsService");
    RlqsClientPool rlqsClientPool = rlqsClientPoolRef.get();
    if (rlqsClientPool == null) {
      // Being shut down, return no interceptor.
      return null;
    }
    rlqsClientPool.addClient(config.rlqsService());

    // final GrpcAuthorizationEngine authEngine = new GrpcAuthorizationEngine(config);
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        // Notes:
        // map domain() -> an incarnation of bucket matchers, f.e. new RlqsEngine(domain, matchers).
        // shared resource holder, acquire every rpc
        // Store RLQS Client or channel in the config as a reference - FilterConfig config ref
        // when parse.
        //   - atomic maybe
        //   - allocate channel on demand / ref counting
        //   - and interface to notify service interceptor on shutdown
        //   - destroy channel when ref count 0
        // potentially many RLQS Clients sharing a channel to grpc RLQS service -
        //   TODO(sergiitk): look up how cache is looked up
        // now we create filters every RPC. will be change in RBAC.
        //    we need to avoid recreating filter when config doesn't change
        //    m: trigger close() after we create new instances
        //    RBAC filter recreate? - has to be fixed for RBAC
        // AI: follow up with Eric on how cache is shared, this changes if we need to cache
        //     interceptor
        // AI: discuss the lifetime of RLQS channel and the cache - needs wider per-lang discussion.

        // Example:
        // AuthDecision authResult = authEngine.evaluate(headers, call);
        // if (logger.isLoggable(Level.FINE)) {
        //   logger.log(Level.FINE,
        //       "Authorization result for serverCall {0}: {1}, matching policy: {2}.",
        //       new Object[]{call, authResult.decision(), authResult.matchingPolicyName()});
        // }
        // if (GrpcAuthorizationEngine.Action.DENY.equals(authResult.decision())) {
        //   Status status = Status.PERMISSION_DENIED.withDescription("Access Denied");
        //   call.close(status, new Metadata());
        //   return new ServerCall.Listener<ReqT>(){};
        // }
        return next.startCall(call, headers);
      }
    };
  }

  @VisibleForTesting
  static RlqsFilterConfig parseRlqsFilter(RateLimitQuotaFilterConfig rlqsFilterProto)
      throws ResourceInvalidException {
    RlqsFilterConfig.Builder builder = RlqsFilterConfig.builder();
    if (rlqsFilterProto.getDomain().isEmpty()) {
      throw new ResourceInvalidException("RateLimitQuotaFilterConfig domain is required");
    }
    builder.domain(rlqsFilterProto.getDomain())
        .rlqsService(GrpcService.fromEnvoyProto(rlqsFilterProto.getRlqsServer()));

    // TODO(sergiitk): bucket_matchers.

    return builder.build();
  }

  @VisibleForTesting
  static RlqsFilterConfig parseRlqsFilterOverride(RateLimitQuotaOverride rlqsFilterProtoOverride)
      throws ResourceInvalidException {
    RlqsFilterConfig.Builder builder = RlqsFilterConfig.builder();
    // TODO(sergiitk): bucket_matchers.

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

