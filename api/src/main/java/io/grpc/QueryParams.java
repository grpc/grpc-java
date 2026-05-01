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
import java.util.Iterator;
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
 *   <li>A key {@code "name"} with value {@code "John Doe"}
 *   <li>A key {@code "role"} with value {@code "admin"}
 *   <li>A second key named {@code "role"} with value {@code "user"}
 *   <li>"Lone" key {@code "active"} without a value.
 * </ul>
 *
 * <p>Instances are not safe for concurrent access by multiple threads.
 */
@Internal
public final class QueryParams {

  private static final String UTF_8 = "UTF-8";
  private final List<Entry> entries = new ArrayList<>();

  /** Creates a new, empty {@code QueryParameters} instance. */
  public QueryParams() {}

  /**
   * Parses a raw query string into a {@code QueryParameters} instance.
   *
   * <p>The input is split on {@code '&'} and each parameter is parsed as either a key/value pair
   * (if it contains an equals sign) or a "lone" key (if it does not).
   *
   * @param rawQuery the raw query component to parse, must not be null
   * @return a new {@code QueryParameters} instance containing the parsed parameters
   */
  public static QueryParams parseRawQueryString(String rawQuery) {
    checkNotNull(rawQuery, "rawQuery");
    QueryParams params = new QueryParams();
    if (!rawQuery.isEmpty()) {
      for (String part : Splitter.on('&').split(rawQuery)) {
        int equalsIndex = part.indexOf('=');
        if (equalsIndex == -1) {
          params.add(Entry.forRawLoneKey(part));
        } else {
          String rawKey = part.substring(0, equalsIndex);
          String rawValue = part.substring(equalsIndex + 1);
          params.add(Entry.forRawKeyValue(rawKey, rawValue));
        }
      }
    }
    return params;
  }

  /**
   * Returns the last parameter in the parameters list having the specified key.
   *
   * @param key the key to search for (non-encoded)
   * @return the matching {@link Entry}, or {@code null} if no match is found
   */
  @Nullable
  public Entry getLast(String key) {
    checkNotNull(key, "key");
    for (int i = entries.size() - 1; i >= 0; --i) {
      Entry entry = entries.get(i);
      if (entry.getKey().equals(key)) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Appends 'entry' to the list of query parameters.
   *
   * @param entry the entry to add
   */
  public void add(Entry entry) {
    entries.add(checkNotNull(entry, "entry"));
  }

  /**
   * Removes all entries equal to the specified entry.
   *
   * <p>Two entries are considered equal if they have the same key and value *after* any URL
   * decoding has been performed.
   *
   * @param entry the entry to remove, must not be null
   * @return the number of entries removed
   */
  public int removeAll(Entry entry) {
    checkNotNull(entry, "entry");
    int removed = 0;
    Iterator<Entry> it = entries.iterator();
    while (it.hasNext()) {
      if (it.next().equals(entry)) {
        it.remove();
        removed++;
      }
    }
    return removed;
  }

  /**
   * Returns the "raw" query string representation of these parameters, suitable for passing to the
   * {@link io.grpc.Uri.Builder#setRawQuery} method.
   *
   * @return the raw query string
   */
  public String toRawQueryString() {
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

  /** Returns true if and only if there are zero entries in this collection. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  @Override
  public String toString() {
    return toRawQueryString();
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

    /**
     * Creates a new key/value pair entry.
     *
     * <p>Both key and value can contain any character. They will be URL encoded for you later, if
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
      return Objects.equals(key, entry.key) && Objects.equals(value, entry.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, value);
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
