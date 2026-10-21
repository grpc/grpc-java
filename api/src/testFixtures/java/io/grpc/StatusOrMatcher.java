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

package io.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import org.mockito.ArgumentMatcher;

/**
 * Mockito matcher for {@link StatusOr}.
 */
public final class StatusOrMatcher<T> implements ArgumentMatcher<StatusOr<T>> {
  public static <T> StatusOrMatcher<T> hasValue(ArgumentMatcher<T> valueMatcher) {
    return new StatusOrMatcher<T>(checkNotNull(valueMatcher, "valueMatcher"), null);
  }

  public static <T> StatusOrMatcher<T> hasStatus(ArgumentMatcher<Status> statusMatcher) {
    return new StatusOrMatcher<T>(null, checkNotNull(statusMatcher, "statusMatcher"));
  }

  private final ArgumentMatcher<T> valueMatcher;
  private final ArgumentMatcher<Status> statusMatcher;

  private StatusOrMatcher(ArgumentMatcher<T> valueMatcher, ArgumentMatcher<Status> statusMatcher) {
    this.valueMatcher = valueMatcher;
    this.statusMatcher = statusMatcher;
  }

  @Override
  public boolean matches(StatusOr<T> statusOr) {
    if (statusOr == null) {
      return false;
    }
    if (statusOr.hasValue() != (valueMatcher != null)) {
      return false;
    }
    if (valueMatcher != null) {
      return valueMatcher.matches(statusOr.getValue());
    } else {
      return statusMatcher.matches(statusOr.getStatus());
    }
  }

  @Override
  public String toString() {
    if (valueMatcher != null) {
      return "{value=" + valueMatcher + "}";
    } else {
      return "{status=" + statusMatcher + "}";
    }
  }
}
