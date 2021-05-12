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

package io.grpc.xds;

import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;

/** This data structure provides request arguments to be evaluated in routing selection or
 *  authorization/http-filtering, to support the {@link io.grpc.xds.Matcher} logic. */
public abstract class EvaluateArgs {
  /** Provides the source address. */
  public String getSourceAddress() {
    throw new UnsupportedOperationException();
  }

  /** Provides the destination address. */
  public String getDestinationAddress() {
    throw new UnsupportedOperationException();
  }

  /** Provides the destination port from the request. */
  public int getDestinationPort() {
    throw new UnsupportedOperationException();
  }

  /** Provides the authenticated principal name. */
  public String getPrincipalName() {
    throw new UnsupportedOperationException();
  }

  /** Provides request header for the corresponding key. */
  @Nullable
  public String getHeader(String key) {
    throw new UnsupportedOperationException();
  }

  /** Provides request method name. */
  public String getFullMethodName() {
    throw new UnsupportedOperationException();
  }

  /** Extract Envoy Attributes from EvaluateArgs.
   * TODO: CEL auth related, may be deleted. */
  public ImmutableMap<String, Object> generateEnvoyAttributes() {
    return ImmutableMap.of();
  }
}
