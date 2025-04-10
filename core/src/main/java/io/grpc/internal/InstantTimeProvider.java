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

package io.grpc.internal;

import static com.google.common.math.LongMath.saturatedAdd;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * {@link InstantTimeProvider} resolves InstantTimeProvider which implements {@link TimeProvider}.
 */
final class InstantTimeProvider implements TimeProvider {
  @Override
  @IgnoreJRERequirement
  public long currentTimeNanos() {
    Instant now = Instant.now();
    long epochSeconds = now.getEpochSecond();
    return saturatedAdd(TimeUnit.SECONDS.toNanos(epochSeconds), now.getNano());
  }
}
