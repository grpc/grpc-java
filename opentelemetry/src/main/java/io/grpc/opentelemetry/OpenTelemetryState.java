/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.opentelemetry;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import javax.annotation.Nullable;

final class OpenTelemetryState {

  /* Client Metrics */
  DoubleHistogram clientCallDuration;
  LongCounter clientAttemptCount;
  DoubleHistogram clientAttemptDuration;
  LongHistogram clientTotalSentCompressedMessageSize;
  LongHistogram clientTotalReceivedCompressedMessageSize;

  /* Server Metrics */
  LongCounter serverCallCount;
  DoubleHistogram serverCallDuration;
  LongHistogram serverTotalSentCompressedMessageSize;
  LongHistogram serverTotalReceivedCompressedMessageSize;

  private OpenTelemetryState(Builder builder) {
    this(builder.clientCallDurationCounter,
        builder.clientAttemptCountCounter,
        builder.clientAttemptDurationCounter,
        builder.clientTotalSentCompressedMessageSizeCounter,
        builder.clientTotalReceivedCompressedMessageSizeCounter,
        builder.serverCallCountCounter,
        builder.serverCallDurationCounter,
        builder.serverTotalSentCompressedMessageSizeCounter,
        builder.serverTotalReceivedCompressedMessageSizeCounter);
  }

  OpenTelemetryState(@Nullable DoubleHistogram clientCallDuration,
      @Nullable LongCounter clientAttemptCount,
      @Nullable DoubleHistogram clientAttemptDuration,
      @Nullable LongHistogram clientTotalSentCompressedMessageSize,
      @Nullable LongHistogram clientTotalReceivedCompressedMessageSize,
      @Nullable LongCounter serverCallCount, @Nullable DoubleHistogram serverCallDuration,
      @Nullable LongHistogram serverTotalSentCompressedMessageSize,
      @Nullable LongHistogram serverTotalReceivedCompressedMessageSize) {
    this.clientCallDuration = clientCallDuration;
    this.clientAttemptCount = clientAttemptCount;
    this.clientAttemptDuration = clientAttemptDuration;
    this.clientTotalSentCompressedMessageSize = clientTotalSentCompressedMessageSize;
    this.clientTotalReceivedCompressedMessageSize = clientTotalReceivedCompressedMessageSize;
    this.serverCallCount = serverCallCount;
    this.serverCallDuration = serverCallDuration;
    this.serverTotalSentCompressedMessageSize = serverTotalSentCompressedMessageSize;
    this.serverTotalReceivedCompressedMessageSize = serverTotalReceivedCompressedMessageSize;
  }

  public static class Builder {

    private DoubleHistogram clientCallDurationCounter;
    private LongCounter clientAttemptCountCounter;
    private DoubleHistogram clientAttemptDurationCounter;
    private LongHistogram clientTotalSentCompressedMessageSizeCounter;
    private LongHistogram clientTotalReceivedCompressedMessageSizeCounter;

    /* Server Metrics */
    private LongCounter serverCallCountCounter;
    private DoubleHistogram serverCallDurationCounter;
    private LongHistogram serverTotalSentCompressedMessageSizeCounter;
    private LongHistogram serverTotalReceivedCompressedMessageSizeCounter;

    Builder clientCallDurationCounter(DoubleHistogram counter) {
      this.clientCallDurationCounter = counter;
      return this;
    }

    Builder clientAttemptCountCounter(LongCounter counter) {
      this.clientAttemptCountCounter = counter;
      return this;
    }

    Builder clientAttemptDurationCounter(DoubleHistogram counter) {
      this.clientAttemptDurationCounter = counter;
      return this;
    }

    Builder clientTotalSentCompressedMessageSizeCounter(LongHistogram counter) {
      this.clientTotalSentCompressedMessageSizeCounter = counter;
      return this;
    }

    Builder clientTotalReceivedCompressedMessageSizeCounter(
        LongHistogram counter) {
      this.clientTotalReceivedCompressedMessageSizeCounter = counter;
      return this;
    }

    Builder serverCallCountCounter(LongCounter counter) {
      this.serverCallCountCounter = counter;
      return this;
    }

    Builder serverCallDurationCounter(DoubleHistogram counter) {
      this.serverCallDurationCounter = counter;
      return this;
    }

    Builder serverTotalSentCompressedMessageSizeCounter(LongHistogram counter) {
      this.serverTotalSentCompressedMessageSizeCounter = counter;
      return this;
    }

    Builder serverTotalReceivedCompressedMessageSizeCounter(
        LongHistogram counter) {
      this.serverTotalReceivedCompressedMessageSizeCounter = counter;
      return this;
    }

    OpenTelemetryState build() {
      return new OpenTelemetryState(this);
    }
  }
}
