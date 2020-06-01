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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.MethodDescriptor;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.propagation.BinaryFormat;
import java.util.ArrayList;
import java.util.List;

public final class CensusClientInterceptor {
  static final CallOptions.Key<Boolean> DISABLE_CLIENT_DEFAULT_CENSUS_STATS =
      CallOptions.Key.create("Disable default census stats");
  static final CallOptions.Key<Boolean> DISABLE_CLIENT_DEFAULT_CENSUS_TRACING =
      CallOptions.Key.create("Disable default census tracing");

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  // Prevent instantiation
  private CensusClientInterceptor() {
  }

  public static class Builder {
    private static final ClientInterceptor NOOP_INTERCEPTOR = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return next.newCall(method, callOptions);
      }
    };

    private boolean statsEnabled;
    private boolean recordStartedRpcs;
    private boolean recordFinishedRpcs;
    private boolean recordRealTimeMetrics;
    private Tagger tagger = Tags.getTagger();
    private TagContextBinarySerializer tagCtxSerializer =
        Tags.getTagPropagationComponent().getBinarySerializer();
    private StatsRecorder statsRecorder = Stats.getStatsRecorder();
    private Supplier<Stopwatch> stopwatchSupplier = STOPWATCH_SUPPLIER;
    private boolean propagateTags = true;

    private boolean tracingEnabled;
    private Tracer tracer = Tracing.getTracer();
    private BinaryFormat binaryFormat = Tracing.getPropagationComponent().getBinaryFormat();

    public Builder setStatsEnabled(boolean value) {
      statsEnabled = value;
      return this;
    }

    public Builder setRecordStartedRpcs(boolean value)  {
      recordStartedRpcs = value;
      return this;
    }

    public Builder setRecordFinishedRpcs(boolean value) {
      recordFinishedRpcs = value;
      return this;
    }

    public Builder setRecordRealTimeMetrics(boolean value) {
      recordRealTimeMetrics = value;
      return this;
    }

    public Builder setTracingEnabled(boolean value) {
      tracingEnabled = value;
      return this;
    }

    @VisibleForTesting
    Builder setTagger(Tagger tagger) {
      this.tagger = tagger;
      return this;
    }

    @VisibleForTesting
    Builder setTagCtxSerializer(TagContextBinarySerializer tagCtxSerializer) {
      this.tagCtxSerializer = tagCtxSerializer;
      return this;
    }

    @VisibleForTesting
    Builder setStatsRecorder(StatsRecorder statsRecorder) {
      this.statsRecorder = statsRecorder;
      return this;
    }

    @VisibleForTesting
    Builder setStopwatchSupplier(Supplier<Stopwatch> stopwatchSupplier) {
      this.stopwatchSupplier = stopwatchSupplier;
      return this;
    }

    @VisibleForTesting
    Builder setPropagateTags(boolean propagateTags) {
      this.propagateTags = propagateTags;
      return this;
    }

    @VisibleForTesting
    Builder setTracer(Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    @VisibleForTesting
    Builder setBinaryFormat(BinaryFormat binaryFormat) {
      this.binaryFormat = binaryFormat;
      return this;
    }

    public ClientInterceptor build() {
      List<ClientInterceptor> interceptors = new ArrayList<>();
      if (statsEnabled) {
        CensusStatsModule censusStats =
            new CensusStatsModule(
                tagger,
                tagCtxSerializer,
                statsRecorder,
                stopwatchSupplier,
                propagateTags,
                recordStartedRpcs,
                recordFinishedRpcs,
                recordRealTimeMetrics);
        interceptors.add(censusStats.getClientInterceptor());
      }
      if (tracingEnabled) {
        CensusTracingModule censusTracing = new CensusTracingModule(tracer, binaryFormat);
        interceptors.add(censusTracing.getClientInterceptor());
      }
      if (interceptors.isEmpty()) {
        interceptors.add(NOOP_INTERCEPTOR);
      }
      return new CustomConfigCensusClientInterceptor(
          interceptors.toArray(new ClientInterceptor[0]));
    }

    private static final class CustomConfigCensusClientInterceptor implements ClientInterceptor {

      private final ClientInterceptor[] interceptors;

      CustomConfigCensusClientInterceptor(ClientInterceptor... interceptors) {
        this.interceptors = interceptors;
      }

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        for (ClientInterceptor interceptor : interceptors) {
          next = ClientInterceptors.intercept(next, interceptors);
        }
        return next.newCall(
            method,
            callOptions
                .withOption(DISABLE_CLIENT_DEFAULT_CENSUS_STATS, true)
                .withOption(DISABLE_CLIENT_DEFAULT_CENSUS_TRACING, true));
      }
    }
  }
}
