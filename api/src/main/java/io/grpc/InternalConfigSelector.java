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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

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
    private final Status status;
    private final Object config;
    @Nullable
    public ClientInterceptor interceptor;

    private Result(
        Status status, Object config, ClientInterceptor interceptor) {
      this.status = checkNotNull(status, "status");
      this.config = config;
      this.interceptor = interceptor;
    }

    /**
     * Creates a {@code Result} with the given error status.
     */
    public static Result forError(Status status) {
      checkArgument(!status.isOk(), "status is OK");
      return new Result(status, null, null);
    }

    /**
     * Returns the status of the config selection operation. If status is not {@link Status#OK},
     * this result should not be used.
     */
    public Status getStatus() {
      return status;
    }

    /**
     * Returns a parsed config. Must have been returned via
     * ServiceConfigParser.parseServiceConfig().getConfig()
     */
    public Object getConfig() {
      return config;
    }

    /**
     * Returns an interceptor that will be applies to calls.
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
       * Sets the parsed config. This field is required.
       *
       * @return this
       */
      public Builder setConfig(Object config) {
        this.config = checkNotNull(config, "config");
        return this;
      }

      /**
       * Sets the interceptor. This field is optional.
       *
       * @return this
       */
      public Builder setInterceptor(ClientInterceptor interceptor) {
        this.interceptor = checkNotNull(interceptor, "interceptor");
        return this;
      }

      /**
       * Build this {@link Result}.
       */
      public Result build() {
        checkState(config != null, "config is not set");
        return new Result(Status.OK, config, interceptor);
      }
    }
  }
}
