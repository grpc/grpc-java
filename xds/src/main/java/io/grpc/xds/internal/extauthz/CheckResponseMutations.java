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

package io.grpc.xds.internal.extauthz;

import com.google.auto.value.AutoValue;
import io.grpc.xds.internal.headermutations.HeaderMutations;

/**
 * A collection of header mutations for an external authorization response.
 * It contains separate mutations for request headers and response headers.
 */
@AutoValue
abstract class CheckResponseMutations {

  static CheckResponseMutations create(HeaderMutations requestMutations,
      HeaderMutations responseMutations) {
    return new AutoValue_CheckResponseMutations(requestMutations, responseMutations);
  }

  public abstract HeaderMutations requestMutations();

  public abstract HeaderMutations responseMutations();
}
