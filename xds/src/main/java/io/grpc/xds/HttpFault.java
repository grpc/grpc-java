/*
 * Copyright 2021 The gRPC Authors
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import io.grpc.xds.Matchers.HeaderMatcher;
import java.util.List;
import javax.annotation.Nullable;

/** Fault injection configurations. */
@AutoValue
abstract class HttpFault {
  @Nullable
  abstract FaultDelay faultDelay();

  @Nullable
  abstract FaultAbort faultAbort();

  abstract String upstreamCluster();

  abstract ImmutableList<String> downstreamNodes();

  abstract ImmutableList<HeaderMatcher> headers();

  @Nullable
  abstract Integer maxActiveFaults();

  static HttpFault create(@Nullable FaultDelay faultDelay, @Nullable FaultAbort faultAbort,
      String upstreamCluster, List<String> downstreamNodes, List<HeaderMatcher> headers,
      @Nullable Integer maxActiveFaults) {
    return new AutoValue_HttpFault(faultDelay, faultAbort, upstreamCluster,
        ImmutableList.copyOf(downstreamNodes), ImmutableList.copyOf(headers), maxActiveFaults);
  }

  /** Fault configurations for aborting requests. */
  @AutoValue
  abstract static class FaultDelay {
    @Nullable
    abstract Long delayNanos();

    abstract boolean headerDelay();

    abstract int ratePerMillion();

    static FaultDelay forFixedDelay(long delayNanos, int ratePerMillion) {
      return FaultDelay.create(delayNanos, false, ratePerMillion);
    }

    static FaultDelay forHeader(int ratePerMillion) {
      return FaultDelay.create(null, true, ratePerMillion);
    }

    private static FaultDelay create(
        @Nullable Long delayNanos, boolean headerDelay, int ratePerMillion) {
      return new AutoValue_HttpFault_FaultDelay(delayNanos, headerDelay, ratePerMillion);
    }
  }

  /** Fault configurations for delaying requests. */
  @AutoValue
  abstract static class FaultAbort {
    @Nullable
    abstract Status status();

    abstract boolean headerAbort();

    abstract int ratePerMillion();

    static FaultAbort forStatus(Status status, int ratePerMillion) {
      checkNotNull(status, "status");
      return FaultAbort.create(status, false, ratePerMillion);
    }

    static FaultAbort forHeader(int ratePerMillion) {
      return FaultAbort.create(null, true, ratePerMillion);
    }

    private static FaultAbort create(
        @Nullable Status status, boolean headerAbort, int ratePerMillion) {
      return new AutoValue_HttpFault_FaultAbort(status, headerAbort, ratePerMillion);
    }
  }
}
