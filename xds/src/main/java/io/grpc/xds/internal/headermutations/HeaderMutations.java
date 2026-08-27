/*
 * Copyright 2025 The gRPC Authors
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
import com.google.common.collect.ImmutableList;

/** A collection of header mutations. */
@AutoValue
public abstract class HeaderMutations {

  public static HeaderMutations create(ImmutableList<HeaderValueOption> headers,
      ImmutableList<String> headersToRemove) {
    return new AutoValue_HeaderMutations(headers, headersToRemove);
  }

  public abstract ImmutableList<HeaderValueOption> headers();

  public abstract ImmutableList<String> headersToRemove();
}
