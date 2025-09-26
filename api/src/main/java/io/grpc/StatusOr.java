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

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Either a Status or a value. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/11563")
@NullMarked
public class StatusOr<T extends @Nullable Object> {
  private StatusOr(@Nullable Status status, @Nullable T value) {
    assert status == null || value == null : "is `status` is not `null` then `value` should be `null`";
    this.status = status;
    this.value = value;
  }

  /** Construct from a value. */
  public static <T extends @Nullable Object> StatusOr<T> fromValue(T value) {
    return new StatusOr<>(null, value);
  }

  /** Construct from a non-Ok status. */
  public static <T extends @Nullable Object> StatusOr<T> fromStatus(Status status) {
    StatusOr<T> result = new StatusOr<>(checkNotNull(status, "status"), null);
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
    if (status != null) {
      throw new IllegalStateException("No value present.");
    }
    return value;
  }

  /** Returns the status. If there is a value (which can be null), returns OK. */
  public Status getStatus() {
    return status == null ? Status.OK : status;
  }

  /**
   * Note that StatusOr containing statuses, the equality comparision is delegated to
   * {@link Status#equals} which just does a reference equality check because equality on
   * Statuses is not well defined.
   * Instead, do comparison based on their Code with {@link Status#getCode}.  The description and
   * cause of the Status are unlikely to be stable, and additional fields may be added to Status
   * in the future.
   */
  @Override
  public boolean equals(Object other) {
    if (!(other instanceof StatusOr)) {
      return false;
    }
    StatusOr<?> otherStatus = (StatusOr<?>) other;
    if (hasValue() != otherStatus.hasValue()) {
      return false;
    }
    if (hasValue()) {
      return Objects.equal(value, otherStatus.value);
    }
    return Objects.equal(status, otherStatus.status);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(status, value);
  }

  @Override
  public String toString() {
    ToStringHelper stringHelper = MoreObjects.toStringHelper(this);
    if (status == null) {
      stringHelper.add("value", value);
    } else {
      stringHelper.add("error", status);
    }
    return stringHelper.toString();
  }

  private final @Nullable Status status;
  private final @Nullable T value;
}
