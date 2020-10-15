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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.re2j.Pattern;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link RouteMatch} represents a group of routing rules used by a logical route to filter RPCs.
 */
final class RouteMatch {
  private final PathMatcher pathMatch;
  private final List<HeaderMatcher> headerMatchers;
  @Nullable
  private final FractionMatcher fractionMatch;

  @VisibleForTesting
  RouteMatch(PathMatcher pathMatch, List<HeaderMatcher> headerMatchers,
      @Nullable FractionMatcher fractionMatch) {
    this.pathMatch = pathMatch;
    this.fractionMatch = fractionMatch;
    this.headerMatchers = headerMatchers;
  }

  static RouteMatch withPathExactOnly(String pathExact) {
    return new RouteMatch(PathMatcher.fromPath(pathExact, true),
        Collections.<HeaderMatcher>emptyList(), null);
  }

  /**
   * Returns {@code true} if a request with the given path and headers passes all the rules
   * specified by this RouteMatch.
   *
   * <p>The request's headers are given as a key-values mapping, where multiple values can
   * be mapped to the same key.
   *
   * <p>Match is not deterministic if a runtime fraction match rule presents in this RouteMatch.
   */
  boolean matches(String path, Map<String, Iterable<String>> headers) {
    if (!pathMatch.matches(path)) {
      return false;
    }
    for (HeaderMatcher headerMatcher : headerMatchers) {
      Iterable<String> headerValues = headers.get(headerMatcher.getName());
      // Special cases for hiding headers: "grpc-previous-rpc-attempts".
      if (headerMatcher.getName().equals("grpc-previous-rpc-attempts")) {
        headerValues = null;
      }
      // Special case for exposing headers: "content-type".
      if (headerMatcher.getName().equals("content-type")) {
        headerValues = Collections.singletonList("application/grpc");
      }
      if (!headerMatcher.matchesValue(headerValues)) {
        return false;
      }
    }
    return fractionMatch == null || fractionMatch.matches();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RouteMatch that = (RouteMatch) o;
    return Objects.equals(pathMatch, that.pathMatch)
        && Objects.equals(fractionMatch, that.fractionMatch)
        && Objects.equals(headerMatchers, that.headerMatchers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pathMatch, fractionMatch, headerMatchers);
  }

  @Override
  public String toString() {
    ToStringHelper toStringHelper =
        MoreObjects.toStringHelper(this).add("pathMatch", pathMatch);
    if (fractionMatch != null) {
      toStringHelper.add("fractionMatch", fractionMatch);
    }
    return toStringHelper.add("headerMatchers", headerMatchers).toString();
  }

  static final class PathMatcher {
    // Exactly one of the following fields is non-null.
    @Nullable
    private final String path;
    @Nullable
    private final String prefix;
    @Nullable
    private final Pattern regEx;
    private final boolean caseSensitive;

    private PathMatcher(@Nullable String path, @Nullable String prefix, @Nullable Pattern regEx,
        boolean caseSensitive) {
      this.path = path;
      this.prefix = prefix;
      this.regEx = regEx;
      this.caseSensitive = caseSensitive;
    }

    static PathMatcher fromPath(String path, boolean caseSensitive) {
      return new PathMatcher(path, null, null, caseSensitive);
    }

    static PathMatcher fromPrefix(String prefix, boolean caseSensitive) {
      return new PathMatcher(null, prefix, null, caseSensitive);
    }

    static PathMatcher fromRegEx(Pattern regEx) {
      return new PathMatcher(null, null, regEx, false /* doesn't matter */);
    }

    boolean matches(String fullMethodName) {
      if (path != null) {
        return caseSensitive ? path.equals(fullMethodName) : path.equalsIgnoreCase(fullMethodName);
      } else if (prefix != null) {
        return caseSensitive
            ? fullMethodName.startsWith(prefix)
            : fullMethodName.toLowerCase().startsWith(prefix.toLowerCase());
      }
      return regEx.matches(fullMethodName);
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
          && Objects.equals(caseSensitive, that.caseSensitive)
          && Objects.equals(
              regEx == null ? null : regEx.pattern(),
              that.regEx == null ? null : that.regEx.pattern());
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, prefix, caseSensitive, regEx == null ? null : regEx.pattern());
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper =
          MoreObjects.toStringHelper(this);
      if (path != null) {
        toStringHelper.add("path", path).add("caseSensitive", caseSensitive);
      }
      if (prefix != null) {
        toStringHelper.add("prefix", prefix).add("caseSensitive", caseSensitive);
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

    private boolean matchesValue(@Nullable Iterable<String> values) {
      if (presentMatch != null) {
        return (values == null) == presentMatch.equals(isInvertedMatch);
      }
      if (values == null) {
        return false;
      }
      String valueStr = Joiner.on(",").join(values);
      boolean baseMatch;
      if (exactMatch != null) {
        baseMatch = exactMatch.equals(valueStr);
      } else if (safeRegExMatch != null) {
        baseMatch = safeRegExMatch.matches(valueStr);
      } else if (rangeMatch != null) {
        long numValue;
        try {
          numValue = Long.parseLong(valueStr);
          baseMatch = rangeMatch.contains(numValue);
        } catch (NumberFormatException ignored) {
          baseMatch = false;
        }
      } else if (prefixMatch != null) {
        baseMatch = valueStr.startsWith(prefixMatch);
      } else {
        baseMatch = valueStr.endsWith(suffixMatch);
      }
      return baseMatch != isInvertedMatch;
    }

    String getName() {
      return name;
    }

    String getExactMatch() {
      return exactMatch;
    }

    Pattern getRegExMatch() {
      return safeRegExMatch;
    }

    Range getRangeMatch() {
      return rangeMatch;
    }

    Boolean getPresentMatch() {
      return presentMatch;
    }

    String getPrefixMatch() {
      return prefixMatch;
    }

    String getSuffixMatch() {
      return suffixMatch;
    }

    boolean isInvertedMatch() {
      return isInvertedMatch;
    }

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

      boolean contains(long value) {
        return value >= start && value < end;
      }

      long getStart() {
        return start;
      }

      long getEnd() {
        return end;
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
    private final ThreadSafeRandom rand;

    FractionMatcher(int numerator, int denominator) {
      this(numerator, denominator, ThreadSafeRandomImpl.instance);
    }

    @VisibleForTesting
    FractionMatcher(int numerator, int denominator, ThreadSafeRandom rand) {
      this.numerator = numerator;
      this.denominator = denominator;
      this.rand = rand;
    }

    private boolean matches() {
      return rand.nextInt(denominator) < numerator;
    }

    int getNumerator() {
      return numerator;
    }

    int getDenominator() {
      return denominator;
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
