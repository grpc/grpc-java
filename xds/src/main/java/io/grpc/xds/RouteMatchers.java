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

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.re2j.Pattern;
import java.util.Objects;
import javax.annotation.Nullable;

final class RouteMatchers {

  // Prevent instantiation
  private RouteMatchers() {
  }

  static final class PathMatcher {
    // Exactly one of the following fields is non-null.
    @Nullable
    private final String path;
    @Nullable
    private final String prefix;
    @Nullable
    private final Pattern regEx;

    PathMatcher(@Nullable String path, @Nullable String prefix, @Nullable Pattern regEx) {
      this.path = path;
      this.prefix = prefix;
      this.regEx = regEx;
    }

    @Nullable
    String getPath() {
      return path;
    }

    @Nullable
    String getPrefix() {
      return prefix;
    }

    @Nullable
    Pattern getRegEx() {
      return regEx;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PathMatcher that = (PathMatcher) o;
      return Objects.equals(path, that.path)
          && Objects.equals(prefix, that.prefix)
          && Objects.equals(
              regEx == null ? null : regEx.pattern(),
              that.regEx == null ? null : that.regEx.pattern());
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, prefix, regEx == null ? null : regEx.pattern());
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper =
          MoreObjects.toStringHelper(this);
      if (path != null) {
        toStringHelper.add("path", path);
      }
      if (prefix != null) {
        toStringHelper.add("prefix", prefix);
      }
      if (regEx != null) {
        toStringHelper.add("regEx", regEx.pattern());
      }
      return toStringHelper.toString();
    }
  }

  /**
   * Matching rules for a specific HTTP/2 header.
   */
  static final class HeaderMatcher {
    private final String name;

    // Exactly one of the following fields is non-null.
    @Nullable
    private final String exactMatch;
    @Nullable
    private final Pattern safeRegExMatch;
    @Nullable
    private final Range rangeMatch;
    @Nullable
    private final Boolean presentMatch;
    @Nullable
    private final String prefixMatch;
    @Nullable
    private final String suffixMatch;

    private final boolean isInvertedMatch;

    // TODO(chengyuanzhang): use builder to enforce oneof semantics would be better.
    HeaderMatcher(
        String name,
        @Nullable String exactMatch, @Nullable Pattern safeRegExMatch, @Nullable Range rangeMatch,
        @Nullable Boolean presentMatch, @Nullable String prefixMatch, @Nullable String suffixMatch,
        boolean isInvertedMatch) {
      this.name = name;
      this.exactMatch = exactMatch;
      this.safeRegExMatch = safeRegExMatch;
      this.rangeMatch = rangeMatch;
      this.presentMatch = presentMatch;
      this.prefixMatch = prefixMatch;
      this.suffixMatch = suffixMatch;
      this.isInvertedMatch = isInvertedMatch;
    }

    // TODO (chengyuanzhang): add getters when needed.

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      HeaderMatcher that = (HeaderMatcher) o;
      return Objects.equals(name, that.name)
          && Objects.equals(exactMatch, that.exactMatch)
          && Objects.equals(
              safeRegExMatch == null ? null : safeRegExMatch.pattern(),
              that.safeRegExMatch == null ? null : that.safeRegExMatch.pattern())
          && Objects.equals(rangeMatch, that.rangeMatch)
          && Objects.equals(presentMatch, that.presentMatch)
          && Objects.equals(prefixMatch, that.prefixMatch)
          && Objects.equals(suffixMatch, that.suffixMatch)
          && Objects.equals(isInvertedMatch, that.isInvertedMatch);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          name, exactMatch, safeRegExMatch == null ? null : safeRegExMatch.pattern(),
          rangeMatch, presentMatch, prefixMatch, suffixMatch, isInvertedMatch);
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper =
          MoreObjects.toStringHelper(this).add("name", name);
      if (exactMatch != null) {
        toStringHelper.add("exactMatch", exactMatch);
      }
      if (safeRegExMatch != null) {
        toStringHelper.add("safeRegExMatch", safeRegExMatch.pattern());
      }
      if (rangeMatch != null) {
        toStringHelper.add("rangeMatch", rangeMatch);
      }
      if (presentMatch != null) {
        toStringHelper.add("presentMatch", presentMatch);
      }
      if (prefixMatch != null) {
        toStringHelper.add("prefixMatch", prefixMatch);
      }
      if (suffixMatch != null) {
        toStringHelper.add("suffixMatch", suffixMatch);
      }
      return toStringHelper.add("isInvertedMatch", isInvertedMatch).toString();
    }

    static final class Range {
      private final long start;
      private final long end;

      Range(long start, long end) {
        this.start = start;
        this.end = end;
      }

      @Override
      public int hashCode() {
        return Objects.hash(start, end);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        Range that = (Range) o;
        return Objects.equals(start, that.start)
            && Objects.equals(end, that.end);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("start", start)
            .add("end", end)
            .toString();
      }
    }
  }

  static final class FractionMatcher {
    private final int numerator;
    private final int denominator;

    FractionMatcher(int numerator, int denominator) {
      this.numerator = numerator;
      this.denominator = denominator;
    }

    @Override
    public int hashCode() {
      return Objects.hash(numerator, denominator);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FractionMatcher that = (FractionMatcher) o;
      return Objects.equals(numerator, that.numerator)
          && Objects.equals(denominator, that.denominator);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("numerator", numerator)
          .add("denominator", denominator)
          .toString();
    }
  }
}
