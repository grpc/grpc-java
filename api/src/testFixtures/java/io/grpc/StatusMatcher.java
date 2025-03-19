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
import static com.google.common.base.Preconditions.checkState;

import org.mockito.ArgumentMatcher;

/**
 * Mockito matcher for {@link Status}.
 */
public final class StatusMatcher implements ArgumentMatcher<Status> {
  public static StatusMatcher statusHasCode(ArgumentMatcher<Status.Code> codeMatcher) {
    return new StatusMatcher(codeMatcher, null);
  }

  public static StatusMatcher statusHasCode(Status.Code code) {
    return statusHasCode(new EqualsMatcher<>(code));
  }

  private final ArgumentMatcher<Status.Code> codeMatcher;
  private final ArgumentMatcher<String> descriptionMatcher;

  private StatusMatcher(
      ArgumentMatcher<Status.Code> codeMatcher,
      ArgumentMatcher<String> descriptionMatcher) {
    this.codeMatcher = checkNotNull(codeMatcher, "codeMatcher");
    this.descriptionMatcher = descriptionMatcher;
  }

  public StatusMatcher andDescription(ArgumentMatcher<String> descriptionMatcher) {
    checkState(this.descriptionMatcher == null, "Already has a description matcher");
    return new StatusMatcher(codeMatcher, descriptionMatcher);
  }

  public StatusMatcher andDescription(String description) {
    return andDescription(new EqualsMatcher<>(description));
  }

  public StatusMatcher andDescriptionContains(String substring) {
    return andDescription(new StringContainsMatcher(substring));
  }

  @Override
  public boolean matches(Status status) {
    return status != null
        && codeMatcher.matches(status.getCode())
        && (descriptionMatcher == null || descriptionMatcher.matches(status.getDescription()));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{code=");
    sb.append(codeMatcher);
    if (descriptionMatcher != null) {
      sb.append(", description=");
      sb.append(descriptionMatcher);
    }
    sb.append("}");
    return sb.toString();
  }

  // Use instead of lambda for better error message.
  static final class EqualsMatcher<T> implements ArgumentMatcher<T> {
    private final T obj;

    EqualsMatcher(T obj) {
      this.obj = checkNotNull(obj, "obj");
    }

    @Override
    public boolean matches(Object other) {
      return obj.equals(other);
    }

    @Override
    public String toString() {
      return obj.toString();
    }
  }

  static final class StringContainsMatcher implements ArgumentMatcher<String> {
    private final String needle;

    StringContainsMatcher(String needle) {
      this.needle = checkNotNull(needle, "needle");
    }

    @Override
    public boolean matches(String haystack) {
      if (haystack == null) {
        return false;
      }
      return haystack.contains(needle);
    }

    @Override
    public String toString() {
      return "contains " + needle;
    }
  }
}
