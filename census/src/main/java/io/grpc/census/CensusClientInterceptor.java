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

package io.grpc.census;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.InternalCensus;
import io.grpc.MethodDescriptor;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ClientInterceptor} for configuring client side OpenCensus features with
 * custom settings. Note OpenCensus stats and tracing features are turned on by default
 * if grpc-census artifact is in the runtime classpath. The gRPC core
 * library does not provide public APIs for customized OpenCensus configurations.
 * Use this interceptor to do so. Intended for advanced usages.
 *
 * <p>Applying this interceptor disables the channel's default stats and tracing
 * features. The effectively OpenCensus features are determined by configurations in this
 * interceptor.
 *
 * <p>For the current release, applying this interceptor may have the side effect that
 * effectively disables retry.
 */
// TODO(chengyuanzhang): add ExperimentalApi annotation.
public final class CensusClientInterceptor implements ClientInterceptor {

  private final List<ClientInterceptor> interceptors = new ArrayList<>();

  private CensusClientInterceptor(
      boolean statsEnabled,
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics,
      boolean tracingEnabled) {
    if (statsEnabled) {
      CensusStatsModule censusStats =
          new CensusStatsModule(recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics);
      interceptors.add(censusStats.getClientInterceptor());
    }
    if (tracingEnabled) {
      CensusTracingModule censusTracing = new CensusTracingModule();
      interceptors.add(censusTracing.getClientInterceptor());
    }
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    callOptions = callOptions.withOption(InternalCensus.DISABLE_CLIENT_DEFAULT_CENSUS, true);
    if (!interceptors.isEmpty()) {
      next =
          ClientInterceptors.intercept(next, interceptors.toArray(new ClientInterceptor[0]));
    }
    return next.newCall(method, callOptions);
  }

  /**
   * Creates a new builder for a {@link CensusClientInterceptor}.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A builder for a {@link CensusClientInterceptor}.
   */
  public static class Builder {

    private boolean statsEnabled;
    private boolean recordStartedRpcs;
    private boolean recordFinishedRpcs;
    private boolean recordRealTimeMetrics;
    private boolean tracingEnabled;

    /**
     * Disable or enable stats features. Disabled by default.
     */
    public Builder setStatsEnabled(boolean value) {
      statsEnabled = value;
      return this;
    }

    /**
     * Disable or enable real-time metrics recording. Effective only if {@link #setStatsEnabled}
     * is set to true. Disabled by default.
     */
    public Builder setRecordStartedRpcs(boolean value)  {
      recordStartedRpcs = value;
      return this;
    }

    /**
     * Disable or enable stats recording for RPC completions. Effective only if {@link
     * #setStatsEnabled} is set to true. Disabled by default.
     */
    public Builder setRecordFinishedRpcs(boolean value) {
      recordFinishedRpcs = value;
      return this;
    }

    /**
     * Disable or enable stats recording for RPC upstarts. Effective only if {@link
     * #setStatsEnabled} is set to true. Disabled by default.
     */
    public Builder setRecordRealTimeMetrics(boolean value) {
      recordRealTimeMetrics = value;
      return this;
    }

    /**
     * Disable or enable tracing features. Disabled by default.
     */
    public Builder setTracingEnabled(boolean value) {
      tracingEnabled = value;
      return this;
    }

    /**
     * Builds the {@link CensusClientInterceptor}.
     */
    public CensusClientInterceptor build() {
      return new CensusClientInterceptor(
          statsEnabled, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics,
          tracingEnabled);
    }
  }
}
