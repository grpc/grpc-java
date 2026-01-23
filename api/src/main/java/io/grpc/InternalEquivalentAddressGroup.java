/*
 * Copyright 2026 The gRPC Authors
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

@Internal
public final class InternalEquivalentAddressGroup {
  private InternalEquivalentAddressGroup() {}

  /**
   * Endpoint weight for load balancing purposes. While the type is Long, it must be a valid uint32.
   * Must not be zero. The weight is proportional to the other endpoints; if an endpoint's weight is
   * twice that of another endpoint, it is intended to receive twice the load.
   */
  public static final Attributes.Key<Long> ATTR_WEIGHT = EquivalentAddressGroup.ATTR_WEIGHT;
}
