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

package io.grpc.xds.internal.headermutations;

import com.google.auto.value.AutoValue;
import io.grpc.xds.internal.grpcservice.HeaderValue;

/**
 * Represents a header option to be appended or mutated as part of xDS configuration.
 * Avoids direct dependency on Envoy's proto objects.
 */
@AutoValue
public abstract class HeaderValueOption {

  public static HeaderValueOption create(
      HeaderValue header, HeaderAppendAction appendAction, boolean keepEmptyValue) {
    return new AutoValue_HeaderValueOption(header, appendAction, keepEmptyValue);
  }

  public abstract HeaderValue header();

  public abstract HeaderAppendAction appendAction();

  public abstract boolean keepEmptyValue();

  /**
   * Defines the action to take when appending headers.
   * Mirrors io.envoyproxy.envoy.config.core.v3.HeaderValueOption.HeaderAppendAction.
   */
  public enum HeaderAppendAction {
    APPEND_IF_EXISTS_OR_ADD,
    ADD_IF_ABSENT,
    OVERWRITE_IF_EXISTS_OR_ADD,
    OVERWRITE_IF_EXISTS
  }
}
