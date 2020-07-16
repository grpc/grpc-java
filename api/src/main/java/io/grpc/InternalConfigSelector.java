/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.Nullable;

// The class can not be located in io.grpc.internal since it is used as a cross-module API.
// Otherwise, shading would break it.
/**
 * Per method config selector that the channel or load balancers will use to choose the appropriate
 * config or take config related actions for an RPC.
 */
@Internal
public abstract class InternalConfigSelector {
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<io.grpc.InternalConfigSelector> KEY
      = Attributes.Key.create("io.grpc.config-selector");

  // Use PickSubchannelArgs for SelectConfigArgs for now. May change over time.
  /** Selects the config for an PRC. */
  public abstract Result selectConfig(LoadBalancer.PickSubchannelArgs args);

  public static final class Result {
    private final Object config;
    @Nullable
    private final ClientInterceptor interceptor;

    private Result(Object config, @Nullable ClientInterceptor interceptor) {
      this.config = checkNotNull(config, "config");
      this.interceptor = interceptor;
    }

    /**
     * Returns a parsed config. Must have been returned via
     * ServiceConfigParser.parseServiceConfig().getConfig()
     */
    public Object getConfig() {
      return config;
    }

    /**
     * Returns an interceptor that would be used to modify CallOptions, in addition to monitoring
     * call lifecycle.
     */
    @Nullable
    public ClientInterceptor getInterceptor() {
      return interceptor;
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public static final class Builder {
      private Object config;
      private ClientInterceptor interceptor;

      private Builder() {}

      /**
       * Sets the parsed config.
       *
       * @return this
       */
      public Builder setConfig(Object config) {
        this.config = checkNotNull(config, "config");
        return this;
      }

      /**
       * Sets the interceptor.
       *
       * @return this
       */
      public Builder setInterceptor(@Nullable ClientInterceptor interceptor) {
        this.interceptor = interceptor;
        return this;
      }

      public Result build() {
        return new Result(config, interceptor);
      }
    }
  }
}
