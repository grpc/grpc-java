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
    @Nullable
    private final Object config;
    @Nullable
    private final CallOptions callOptions;
    @Nullable
    private final Runnable committedCallback;

    private Result(
        Status status, Object config, CallOptions callOptions, Runnable committedCallback) {
      this.status = checkNotNull(status, "status");
      this.config = config;
      this.callOptions = callOptions;
      this.committedCallback = committedCallback;
    }

    /**
     * Creates a {@code Result} with the given error status.
     */
    public static Result forError(Status status) {
      checkArgument(!status.isOk(), "status is OK");
      return new Result(status, null, null, null);
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
    @Nullable
    public Object getConfig() {
      return config;
    }

    /**
     * Returns a config-selector-modified CallOptions for the RPC.
     */
    @Nullable
    public CallOptions getCallOptions() {
      return callOptions;
    }

    /**
     * Returns a callback to be invoked when the RPC no longer needs a picker.
     */
    @Nullable
    public Runnable getCommittedCallback() {
      return committedCallback;
    }

    public static Builder newBuilder() {
      return new Builder();
    }

    public static final class Builder {
      private Object config;
      private CallOptions callOptions;
      private Runnable committedCallback;

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
       * Sets the CallOptions.
       *
       * @return this
       */
      public Builder setCallOptions(CallOptions callOptions) {
        this.callOptions = checkNotNull(callOptions, "callOptions");
        return this;
      }

      /**
       * Sets the interceptor.
       *
       * @return this
       */
      public Builder setCommittedCallback(@Nullable Runnable committedCallback) {
        this.committedCallback = committedCallback;
        return this;
      }

      /**
       * Build this {@link Result}.
       */
      public Result build() {
        checkState(config != null, "config is not set");
        checkState(callOptions != null, "callOptions is not set");
        return new Result(Status.OK, config, callOptions, committedCallback);
      }
    }
  }
}
