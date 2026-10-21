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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import javax.annotation.Nullable;

/** An object or a String error. */
final class StructOrError<T> {

  /**
  * Returns a {@link StructOrError} for the successfully converted data object.
  */
  public static <T> StructOrError<T> fromStruct(T struct) {
    return new StructOrError<>(struct);
  }

  /**
   * Returns a {@link StructOrError} for the failure to convert the data object.
   */
  public static <T> StructOrError<T> fromError(String errorDetail) {
    return new StructOrError<>(errorDetail);
  }

  private final String errorDetail;
  private final T struct;

  private StructOrError(T struct) {
    this.struct = checkNotNull(struct, "struct");
    this.errorDetail = null;
  }

  private StructOrError(String errorDetail) {
    this.struct = null;
    this.errorDetail = checkNotNull(errorDetail, "errorDetail");
  }

  /**
   * Returns struct if exists, otherwise null.
   */
  @VisibleForTesting
  @Nullable
  public T getStruct() {
    return struct;
  }

  /**
   * Returns error detail if exists, otherwise null.
   */
  @VisibleForTesting
  @Nullable
  public String getErrorDetail() {
    return errorDetail;
  }
}

