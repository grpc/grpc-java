/*
 * Copyright 2021 The gRPC Authors
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

import com.google.auto.value.AutoValue;
import com.google.re2j.Pattern;
import javax.annotation.Nullable;

/** A group of request matchers. */
final class Matchers {
  private Matchers() {}

  /** Matcher for HTTP request path. */
  @AutoValue
  abstract static class PathMatcher {
    // Exact full path to be matched.
    @Nullable
    abstract String path();

    // Path prefix to be matched.
    @Nullable
    abstract String prefix();

    // Regular expression pattern of the path to be matched.
    @Nullable
    abstract Pattern regEx();

    // Whether case sensitivity is taken into account for matching.
    // Only valid for full path matching or prefix matching.
    abstract boolean caseSensitive();

    static PathMatcher fromPath(String path, boolean caseSensitive) {
      checkNotNull(path, "path");
      return PathMatcher.create(path, null, null, caseSensitive);
    }

    static PathMatcher fromPrefix(String prefix, boolean caseSensitive) {
      checkNotNull(prefix, "prefix");
      return PathMatcher.create(null, prefix, null, caseSensitive);
    }

    static PathMatcher fromRegEx(Pattern regEx) {
      checkNotNull(regEx, "regEx");
      return PathMatcher.create(null, null, regEx, false /* doesn't matter */);
    }

    private static PathMatcher create(@Nullable String path, @Nullable String prefix,
        @Nullable Pattern regEx, boolean caseSensitive) {
      return new AutoValue_Matchers_PathMatcher(path, prefix, regEx, caseSensitive);
    }
  }

  /** Matcher for HTTP request headers. */
  @AutoValue
  abstract static class HeaderMatcher {
    // Name of the header to be matched.
    abstract String name();

    // Matches exact header value.
    @Nullable
    abstract String exactValue();

    // Matches header value with the regular expression pattern.
    @Nullable
    abstract Pattern safeRegEx();

    // Matches header value an integer value in the range.
    @Nullable
    abstract Range range();

    // Matches header presence.
    @Nullable
    abstract Boolean present();

    // Matches header value with the prefix.
    @Nullable
    abstract String prefix();

    // Matches header value with the suffix.
    @Nullable
    abstract String suffix();

    // Whether the matching semantics is inverted. E.g., present && !inverted -> !present
    abstract boolean inverted();

    static HeaderMatcher forExactValue(String name, String exactValue, boolean inverted) {
      checkNotNull(name, "name");
      checkNotNull(exactValue, "exactValue");
      return HeaderMatcher.create(name, exactValue, null, null, null, null, null, inverted);
    }

    static HeaderMatcher forSafeRegEx(String name, Pattern safeRegEx, boolean inverted) {
      checkNotNull(name, "name");
      checkNotNull(safeRegEx, "safeRegEx");
      return HeaderMatcher.create(name, null, safeRegEx, null, null, null, null, inverted);
    }

    static HeaderMatcher forRange(String name, Range range, boolean inverted) {
      checkNotNull(name, "name");
      checkNotNull(range, "range");
      return HeaderMatcher.create(name, null, null, range, null, null, null, inverted);
    }

    static HeaderMatcher forPresent(String name, boolean present, boolean inverted) {
      checkNotNull(name, "name");
      return HeaderMatcher.create(name, null, null, null, present, null, null, inverted);
    }

    static HeaderMatcher forPrefix(String name, String prefix, boolean inverted) {
      checkNotNull(name, "name");
      checkNotNull(prefix, "prefix");
      return HeaderMatcher.create(name, null, null, null, null, prefix, null, inverted);
    }

    static HeaderMatcher forSuffix(String name, String suffix, boolean inverted) {
      checkNotNull(name, "name");
      checkNotNull(suffix, "suffix");
      return HeaderMatcher.create(name, null, null, null, null, null, suffix, inverted);
    }

    private static HeaderMatcher create(String name, @Nullable String exactValue,
        @Nullable Pattern safeRegEx, @Nullable Range range,
        @Nullable Boolean present, @Nullable String prefix,
        @Nullable String suffix, boolean inverted) {
      checkNotNull(name, "name");
      return new AutoValue_Matchers_HeaderMatcher(name, exactValue, safeRegEx, range, present,
          prefix, suffix, inverted);
    }

    /** Represents an integer range. */
    @AutoValue
    abstract static class Range {
      abstract long start();

      abstract long end();

      static Range create(long start, long end) {
        return new AutoValue_Matchers_HeaderMatcher_Range(start, end);
      }

    }
  }

  /** Represents a fractional value. */
  @AutoValue
  abstract static class FractionMatcher {
    abstract int numerator();

    abstract int denominator();

    static FractionMatcher create(int numerator, int denominator) {
      return new AutoValue_Matchers_FractionMatcher(numerator, denominator);
    }
  }
}
