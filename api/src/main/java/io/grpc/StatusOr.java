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

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;

/** Either a Status or a value. */
public class StatusOr<T> {
  private StatusOr(Status status, T value) {
    this.status = status;
    this.value = value;
  }

  /** Construct from a value. */
  public static <T> StatusOr<T> fromValue(T value) {
    StatusOr<T> result = new StatusOr<T>(null, checkNotNull(value, "value"));
    return result;
  }

  /** Construct from a non-Ok status. */
  public static <T> StatusOr<T> fromStatus(Status status) {
    StatusOr<T> result = new StatusOr<T>(checkNotNull(status, "status"), null);
    checkArgument(!status.isOk(), "cannot use OK status: %s", status);
    return result;
  }

  /** Returns whether there is a value. */
  public boolean hasValue() {
    return status == null;
  }

  /**
   * Returns the value if set or throws exception if there is no value set. This method is meant
   * to be called after checking the return value of hasValue() first.
   */
  public T getValue() {
    if (value == null) {
      throw new IllegalStateException("No value present.");
    }
    return value;
  }

  /** Returns the status. If there is a value, returns OK. */
  public Status getStatus() {
    return value != null ? Status.OK : status;
  }

  @Override
  @SuppressWarnings({"ReferenceEquality", "unchecked"})
  public boolean equals(Object other) {
    if (!(other instanceof StatusOr)) {
      return false;
    }
    StatusOr<T> otherStatus = (StatusOr<T>) other;
    if (hasValue() != otherStatus.hasValue()) {
      return false;
    }
    if (hasValue()) {
      return Objects.equal(value, otherStatus.value);
    }
    return status == otherStatus.status;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(status, value);
  }

  private final Status status;
  private final T value;
}
