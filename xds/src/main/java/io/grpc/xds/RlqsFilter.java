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
import static io.grpc.xds.XdsResourceType.ResourceInvalidException;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride;
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import javax.annotation.Nullable;

/** RBAC Http filter implementation. */
final class RlqsFilter implements Filter, ServerInterceptorBuilder {
  // private static final Logger logger = Logger.getLogger(RlqsFilter.class.getName());

  static final RlqsFilter INSTANCE = new RlqsFilter();

  static final String TYPE_URL = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaFilterConfig";
  static final String TYPE_URL_OVERRIDE_CONFIG = "type.googleapis.com/"
      + "envoy.extensions.filters.http.rate_limit_quota.v3.RateLimitQuotaOverride";

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL, TYPE_URL_OVERRIDE_CONFIG };
  }

  @Override
  public ConfigOrError<RlqsFilterConfig> parseFilterConfig(Message rawProtoMessage) {
    try {
      RlqsFilterConfig rlqsFilterConfig =
          parseRlqsFilter(unpackConfigMessage(rawProtoMessage, RateLimitQuotaFilterConfig.class));
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
      RateLimitQuotaOverride rlqsFilterOverrideProto =
          unpackConfigMessage(rawProtoMessage, RateLimitQuotaOverride.class);
      return ConfigOrError.fromConfig(parseRlqsFilterOverride(rlqsFilterOverrideProto));
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Can't unpack RateLimitQuotaFilterConfig proto: " + e);
    } catch (ResourceInvalidException e) {
      return ConfigOrError.fromError(e.getMessage());
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig) {
    checkNotNull(config, "config");
    if (overrideConfig != null) {
      config = overrideConfig;
    }
    // todo
    config.typeUrl(); // used
    return null;
  }

  // @VisibleForTesting
  static RlqsFilterConfig parseRlqsFilterOverride(RateLimitQuotaOverride rlqsFilterProtoOverride)
      throws ResourceInvalidException {
    String domain;
    if (!rlqsFilterProtoOverride.getDomain().isEmpty()) {
      domain = rlqsFilterProtoOverride.getDomain();
    } else {
      domain = "YOYOOYOYO";
    }
    // todo: parse the reset
    return RlqsFilterConfig.create(domain);
  }

  // @VisibleForTesting
  static RlqsFilterConfig parseRlqsFilter(RateLimitQuotaFilterConfig rlqsFilterProto)
      throws ResourceInvalidException {
    if (rlqsFilterProto.getDomain().isEmpty()) {
      throw new ResourceInvalidException("RateLimitQuotaFilterConfig domain is required");
    }
    // TODO(sergiitk): parse rlqs_server, bucket_matchers.
    return RlqsFilterConfig.create(rlqsFilterProto.getDomain());
  }

  private static <T extends com.google.protobuf.Message> T unpackConfigMessage(
      Message message, Class<T> clazz) throws InvalidProtocolBufferException {
    if (!(message instanceof Any)) {
      throw new InvalidProtocolBufferException("Invalid config type: " + message.getClass());
    }
    return ((Any) message).unpack(clazz);
  }
}

