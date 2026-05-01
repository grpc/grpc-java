/*
 * Copyright 2026 The gRPC Authors
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

import com.google.common.base.Splitter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A parser and mutable container class for {@code application/x-www-form-urlencoded}-style URL
 * parameters as conceived by <a href="https://datatracker.ietf.org/doc/html/rfc1866#section-8.2.1">
 * RFC 1866 Section 8.2.1</a>.
 *
 * <p>For example, a URI like {@code "http://who?name=John+Doe&role=admin&role=user&active"} has:
 *
 * <ul>
 *   <li>A key {@code name} with value {@code John Doe}
 *   <li>A key {@code role} with value {@code admin}
 *   <li>A second key named {@code role} with value {@code user}
 *   <li>"Lone" key {@code active} without a value.
 * </ul>
 *
 * <p>This class is meant to be used with {@link io.grpc.Uri}. For example:
 *
 * <pre>{@code
 * Uri uri = Uri.parse("http://who?name=John+Doe&role=admin&role=user&active");
 * QueryParams params = QueryParams.fromRawQuery(uri.getRawQuery());
 * params.asList().removeIf(e -> "role".equals(e.getKey()) && "admin".equals(e.getValue()));
 *
 * Uri modifiedUri = uri.toBuilder().setRawQuery(params.toRawQuery()).build();
 * }</pre>
 *
 * <p>Note that the empty collection is encoded as a null raw query string, which means "absent" to
 * {@link io.grpc.Uri.Builder#setRawQuery}. An empty string query component (""), on the other hand,
 * is modeled as an instance of QueryParams containing a single lone (empty) key. It must be this
 * way if we are to simultaneously 1) support lone keys, 2) have parse/toRawQuery round-trip
 * transparency, and 3) never fail to parse a valid RFC 3986 query component.
 *
 * <p>This container and its {@link Entry} take the same position as {@link io.grpc.Uri} on
 * equality: raw keys and values must match exactly to be equal. Most callers won't care about how
 * keys and values are encoded on the wire and will work with the getters for cooked keys and values
 * instead.
 *
 * <p>Instances are not safe for concurrent access by multiple threads, including by way of the
 * {@link #asList()} view method.
 */
@Internal
public final class QueryParams {

  private static final String UTF_8 = "UTF-8";
  private final List<Entry> entries = new ArrayList<>();

  /** Creates a new, empty {@code QueryParams} instance. */
  public QueryParams() {}

  /**
   * Parses a raw query string into a {@code QueryParams} instance.
   *
   * <p>The input is split on {@code '&'} and each parameter is parsed as either a key/value pair
   * (if it contains an equals sign) or a "lone" key (if it does not).
   *
   * <p>No valid RFC 3986 query component will fail to parse. For example, {@code ===} is parsed as
   * a single parameter with "" as the key and "==" as the value. {@code &&&} is parsed as three
   * lone keys named "". And so on. If {@code rawQuery} is not a valid RFC 3986 query component, the
   * behavior is undefined. But if you are starting with a {@link io.grpc.Uri}, passing the value
   * returned by {@link io.grpc.Uri#getRawQuery()} is always well-defined and will never fail.
   *
   * <p>Calling {@link #toRawQuery()} on the returned object is guaranteed to return exactly {@code
   * rawQuery}.
   *
   * @param rawQuery the raw query component to parse, or null to return an empty container
   * @return a new instance of {@code QueryParams} representing the input
   */
  public static QueryParams fromRawQuery(@Nullable String rawQuery) {
    QueryParams params = new QueryParams();
    if (rawQuery != null) {
      for (String part : Splitter.on('&').split(rawQuery)) {
        int equalsIndex = part.indexOf('=');
        if (equalsIndex == -1) {
          params.entries.add(Entry.forRawLoneKey(part));
        } else {
          String rawKey = part.substring(0, equalsIndex);
          String rawValue = part.substring(equalsIndex + 1);
          params.entries.add(Entry.forRawKeyValue(rawKey, rawValue));
        }
      }
    }
    return params;
  }

  /**
   * Returns a mutable list view of the query parameters.
   *
   * @return the mutable list of entries
   */
  public List<Entry> asList() {
    return entries;
  }

  /**
   * Returns the "raw" query string representation of these parameters, suitable for passing to the
   * {@link io.grpc.Uri.Builder#setRawQuery} method.
   *
   * @return the raw query string
   */
  @Nullable
  public String toRawQuery() {
    if (entries.isEmpty()) {
      return null;
    }
    StringBuilder resultBuilder = new StringBuilder();
    boolean first = true;
    for (Entry entry : entries) {
      if (!first) {
        resultBuilder.append('&');
      }
      entry.appendToRawQueryStringBuilder(resultBuilder);
      first = false;
    }
    return resultBuilder.toString();
  }

  @Override
  public String toString() {
    return entries.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof QueryParams)) {
      return false;
    }
    QueryParams other = (QueryParams) o;
    return entries.equals(other.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  /** A single query parameter entry. */
  public static final class Entry {
    private final String rawKey;
    @Nullable private final String rawValue;
    private final String key;
    @Nullable private final String value;

    private Entry(String rawKey, @Nullable String rawValue, String key, @Nullable String value) {
      this.rawKey = checkNotNull(rawKey, "rawKey");
      this.rawValue = rawValue;
      this.key = checkNotNull(key, "key");
      this.value = value;
    }

    /**
     * Returns the key.
     *
     * <p>Any characters that needed URL encoding have already been decoded.
     */
    public String getKey() {
      return key;
    }

    /**
     * Returns the value, or {@code null} if this is a "lone" key.
     *
     * <p>Any characters that needed URL encoding have already been decoded.
     */
    @Nullable
    public String getValue() {
      return value;
    }

    /** Returns {@code true} if this entry has a value, {@code false} if it is a "lone" key. */
    public boolean hasValue() {
      return value != null;
    }

    /**
     * Creates a new key/value pair entry.
     *
     * <p>Both key and value can contain any character. They will be URL encoded for you if
     * necessary.
     */
    public static Entry forKeyValue(String key, String value) {
      checkNotNull(key, "key");
      checkNotNull(value, "value");
      return new Entry(encode(key), encode(value), key, value);
    }

    /**
     * Creates a new query parameter with a "lone" key.
     *
     * <p>'key' can contain any character. It will be URL encoded for you later, as necessary.
     *
     * @param key the decoded key, must not be null
     * @return a new {@code Entry}
     */
    public static Entry forLoneKey(String key) {
      checkNotNull(key, "key");
      return new Entry(encode(key), null, key, null);
    }

    static Entry forRawKeyValue(String rawKey, String rawValue) {
      checkNotNull(rawKey, "rawKey");
      checkNotNull(rawValue, "rawValue");
      return new Entry(rawKey, rawValue, decode(rawKey), decode(rawValue));
    }

    static Entry forRawLoneKey(String rawKey) {
      checkNotNull(rawKey, "rawKey");
      return new Entry(rawKey, null, decode(rawKey), null);
    }

    void appendToRawQueryStringBuilder(StringBuilder sb) {
      sb.append(rawKey);
      if (rawValue != null) {
        sb.append('=').append(rawValue);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Entry)) {
        return false;
      }
      Entry entry = (Entry) o;
      return Objects.equals(rawKey, entry.rawKey) && Objects.equals(rawValue, entry.rawValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(rawKey, rawValue);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      appendToRawQueryStringBuilder(sb);
      return sb.toString();
    }
  }

  private static String decode(String s) {
    try {
      // TODO: Use URLDecoder.decode(String, Charset) when available
      return URLDecoder.decode(s, UTF_8);
    } catch (UnsupportedEncodingException impossible) {
      throw new AssertionError("UTF-8 is not supported", impossible);
    }
  }

  private static String encode(String s) {
    try {
      // TODO: Use URLEncoder.encode(String, Charset) when available
      return URLEncoder.encode(s, UTF_8);
    } catch (UnsupportedEncodingException impossible) {
      throw new AssertionError("UTF-8 is not supported", impossible);
    }
  }
}
