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

import com.google.auto.value.AutoValue;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import javax.annotation.Nullable;

@AutoValue
abstract class OpenTelemetryMetricsResource {

  /* Client Metrics */
  @Nullable
  abstract DoubleHistogram clientCallDurationCounter();

  @Nullable
  abstract LongCounter clientAttemptCountCounter();

  @Nullable
  abstract DoubleHistogram clientAttemptDurationCounter();

  @Nullable
  abstract LongHistogram clientTotalSentCompressedMessageSizeCounter();

  @Nullable
  abstract LongHistogram clientTotalReceivedCompressedMessageSizeCounter();


  /* Server Metrics */
  @Nullable
  abstract LongCounter serverCallCountCounter();

  @Nullable
  abstract DoubleHistogram serverCallDurationCounter();

  @Nullable
  abstract LongHistogram serverTotalSentCompressedMessageSizeCounter();

  @Nullable
  abstract LongHistogram serverTotalReceivedCompressedMessageSizeCounter();

  static Builder builder() {
    return new AutoValue_OpenTelemetryMetricsResource.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {

    abstract Builder clientCallDurationCounter(DoubleHistogram counter);

    abstract Builder clientAttemptCountCounter(LongCounter counter);

    abstract Builder clientAttemptDurationCounter(DoubleHistogram counter);

    abstract Builder clientTotalSentCompressedMessageSizeCounter(LongHistogram counter);

    abstract Builder clientTotalReceivedCompressedMessageSizeCounter(
        LongHistogram counter);

    abstract Builder serverCallCountCounter(LongCounter counter);

    abstract Builder serverCallDurationCounter(DoubleHistogram counter);

    abstract Builder serverTotalSentCompressedMessageSizeCounter(LongHistogram counter);

    abstract Builder serverTotalReceivedCompressedMessageSizeCounter(
        LongHistogram counter);

    abstract OpenTelemetryMetricsResource build();
  }
}
