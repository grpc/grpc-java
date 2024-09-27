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

package io.grpc.xds.internal.datatype;

import com.google.auto.value.AutoOneOf;
import io.envoyproxy.envoy.type.v3.RateLimitStrategy.BlanketRule;
import io.envoyproxy.envoy.type.v3.TokenBucket;

@AutoOneOf(RateLimitStrategy.Kind.class)
public abstract class RateLimitStrategy {
  // TODO(sergiitk): instead, make RateLimitStrategy interface,
  //   and AllowAll DenyAll, TokenBucket extending it
  public enum Kind { BLANKET_RULE, TOKEN_BUCKET }

  public abstract Kind getKind();

  public final boolean rateLimit() {
    switch (getKind()) {
      case BLANKET_RULE:
        switch (blanketRule()) {
          case DENY_ALL:
            return true;
          case ALLOW_ALL:
          default:
            return false;
        }
      case TOKEN_BUCKET:
        throw new UnsupportedOperationException("Not implemented yet");
      default:
        throw new UnsupportedOperationException("Unexpected strategy kind");
    }
  }

  // TODO(sergiitk): [IMPL] Replace with the internal class.
  public abstract BlanketRule blanketRule();

  // TODO(sergiitk): [IMPL] Replace with the implementation class.
  public abstract TokenBucket tokenBucket();

  public static RateLimitStrategy ofBlanketRule(BlanketRule blanketRuleProto) {
    return AutoOneOf_RateLimitStrategy.blanketRule(blanketRuleProto);
  }

  public static RateLimitStrategy ofTokenBucket(TokenBucket tokenBucketProto) {
    return AutoOneOf_RateLimitStrategy.tokenBucket(tokenBucketProto);
  }

  public static RateLimitStrategy fromEnvoyProto(
      io.envoyproxy.envoy.type.v3.RateLimitStrategy rateLimitStrategyProto) {
    switch (rateLimitStrategyProto.getStrategyCase()) {
      case BLANKET_RULE:
        return ofBlanketRule(rateLimitStrategyProto.getBlanketRule());
      case TOKEN_BUCKET:
        return ofTokenBucket(rateLimitStrategyProto.getTokenBucket());
      case REQUESTS_PER_TIME_UNIT:
        // TODO(sergiitk): [IMPL] convert to token bucket;
        throw new UnsupportedOperationException("Not implemented yet");
      default:
        // TODO(sergiitk): [IMPL[ replace with a custom exception.
        throw new UnsupportedOperationException("Unknown RL type");
    }
  }
}
