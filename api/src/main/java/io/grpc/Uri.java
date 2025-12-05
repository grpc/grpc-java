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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A not-quite-general-purpose representation of a Uniform Resource Identifier (URI), as defined by
 * <a href="https://datatracker.ietf.org/doc/html/rfc3986">RFC 3986</a>.
 *
 * <h1>The URI</h1>
 *
 * <p>A URI identifies a resource by its name or location or both. The resource could be a file,
 * service, or some other abstract entity.
 *
 * <h2>Examples</h2>
 *
 * <ul>
 *   <li><code>http://admin@example.com:8080/controlpanel?filter=users#settings</code>
 *   <li><code>ftp://[2001:db8::7]/docs/report.pdf</code>
 *   <li><code>file:///My%20Computer/Documents/letter.doc</code>
 *   <li><code>dns://8.8.8.8/storage.googleapis.com</code>
 *   <li><code>mailto:John.Doe@example.com</code>
 *   <li><code>tel:+1-206-555-1212</code>
 *   <li><code>urn:isbn:978-1492082798</code>
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <p>This class aims to meet the needs of grpc-java itself and RPC related code that depend on it.
 * It isn't quite general-purpose. It definitely would not be suitable for building an HTTP user
 * agent or proxy server. In particular, it:
 *
 * <ul>
 *   <li>Can only represent a URI, not a "URI-reference" or "relative reference". In other words, a
 *       "scheme" is always required.
 *   <li>Has no knowledge of the particulars of any scheme, with respect to normalization and
 *       comparison. We don't know <code>https://google.com</code> is the same as <code>
 *       https://google.com:443</code>, that <code>file:///</code> is the same as <code>
 *       file://localhost</code>, or that <code>joe@example.com</code> is the same as <code>
 *       joe@EXAMPLE.COM</code>. No one class can or should know everything about every scheme so
 *       all this is better handled at a higher layer.
 *   <li>Implements {@link #equals(Object)} as a char-by-char comparison. Expect false negatives.
 *   <li>Does not support "IPvFuture" literal addresses.
 *   <li>Does not reflect how web browsers parse user input or the <a
 *       href="https://url.spec.whatwg.org/">URL Living Standard</a>.
 *   <li>Does not support different character encodings. Assumes UTF-8 in several places.
 * </ul>
 *
 * <h2>Migrating from RFC 2396 and {@link java.net.URI}</h2>
 *
 * <p>Those migrating from {@link java.net.URI} and/or its primary specification in RFC 2396 should
 * note some differences.
 *
 * <h3>Uniform Hierarchical Syntax</h3>
 *
 * <p>RFC 3986 unifies the older ideas of "hierarchical" and "opaque" URIs into a single generic
 * syntax. What RFC 2396 called an opaque "scheme-specific part" is always broken out by RFC 3986
 * into an authority and path hierarchy, followed by query and fragment components. Accordingly,
 * this class has only getters for those components but no {@link
 * java.net.URI#getSchemeSpecificPart()} analog.
 *
 * <p>The RFC 3986 definition of path is now more liberal to accommodate this:
 *
 * <ul>
 *   <li>Path doesn't have to start with a slash. For example, the path of <code>
 *       urn:isbn:978-1492082798</code> is <code>isbn:978-1492082798</code> even though it doesn't
 *       look much like a file system path.
 *   <li>The path can now be empty. So Android's <code>
 *       intent:#Intent;action=MAIN;category=LAUNCHER;end</code> is now a valid {@link Uri}. Even
 *       the scheme-only <code>about:</code> is now valid.
 * </ul>
 *
 * <p>The uniform syntax always understands what follows a '?' to be a query string. For example,
 * <code>mailto:me@example.com?subject=foo</code> now has a query component whereas RFC 2396
 * considered everything after the <code>mailto:</code> scheme to be opaque.
 *
 * <p>Same goes for fragment. <code>data:image/png;...#xywh=0,0,10,10</code> now has a fragment
 * whereas RFC 2396 considered everything after the scheme to be opaque.
 *
 * <h3>Uniform Authority Syntax</h3>
 *
 * <p>RFC 2396 tried to guess if an authority was a "server" (host:port) or "registry-based"
 * (arbitrary string) based on its contents. RFC 3986 expects every authority to look like
 * [userinfo@]host[:port] and loosens the definition of a "host" to accommodate. Accordingly, this
 * class has no equivalent to {@link java.net.URI#parseServerAuthority()} -- authority was parsed
 * into its components and checked for validity when the {@link Uri} was created.
 *
 * <h3>Other Specific Differences</h3>
 *
 * <p>RFC 2396 does not allow underscores in a host name, meaning {@link java.net.URI} switches to
 * opaque mode when it sees one. {@link Uri} does allow underscores in host, to accommodate
 * registries other than DNS. So <code>http://my_site.com:8080/index.html</code> now parses as a
 * host, port and path rather than a single opaque scheme-specific part.
 *
 * <p>{@link Uri} strictly *requires* square brackets in the query string and fragment to be
 * percent-encoded whereas RFC 2396 merely recommended doing so.
 *
 * <p>Other URx classes are "liberal in what they accept and strict in what they produce." {@link
 * Uri#parse(String)} and {@link Uri#create(String)}, however, are strict in what they accept and
 * transparent when asked to reproduce it via {@link Uri#toString()}. The former policy may be
 * appropriate for parsing user input or web content, but this class is meant for gRPC clients,
 * servers and plugins like name resolvers where human error at runtime is less likely and best
 * detected early. {@link java.net.URI#create(String)} is similarly strict, which makes migration
 * easy, except for the server/registry-based ambiguity addressed by {@link
 * java.net.URI#parseServerAuthority()}.
 *
 * <p>{@link java.net.URI} and {@link Uri} both support IPv6 literals in square brackets as defined
 * by RFC 2732.
 */
@Internal
public final class Uri {
  // Components are stored percent-encoded, just as originally parsed for transparent parse/toString
  // round-tripping.
  private final String scheme; // != null since we don't support relative references.
  @Nullable private final String userInfo;
  @Nullable private final String host;
  @Nullable private final String port;
  private final String path; // In RFC 3986, path is always defined (but can be empty).
  @Nullable private final String query;
  @Nullable private final String fragment;

  private Uri(Builder builder) {
    this.scheme = checkNotNull(builder.scheme, "scheme");
    this.userInfo = builder.userInfo;
    this.host = builder.host;
    this.port = builder.port;
    this.path = builder.path;
    this.query = builder.query;
    this.fragment = builder.fragment;

    // Checks common to the parse() and Builder code paths.
    if (hasAuthority()) {
      if (!path.isEmpty() && !path.startsWith("/")) {
        throw new IllegalArgumentException("Has authority -- Non-empty path must start with '/'");
      }
    } else {
      if (path.startsWith("//")) {
        throw new IllegalArgumentException("No authority -- Path cannot start with '//'");
      }
    }
  }

  /**
   * Parses a URI from its string form.
   *
   * @throws URISyntaxException if 's' is not a valid RFC 3986 URI.
   */
  public static Uri parse(String s) throws URISyntaxException {
    try {
      return create(s);
    } catch (IllegalArgumentException e) {
      throw new URISyntaxException(s, e.getMessage());
    }
  }

  /**
   * Creates a URI from a string assumed to be valid.
   *
   * <p>Useful for defining URI constants in code. Not for user input.
   *
   * @throws IllegalArgumentException if 's' is not a valid RFC 3986 URI.
   */
  public static Uri create(String s) {
    Builder builder = new Builder();
    int i = 0;
    final int n = s.length();

    // 3.1. Scheme: Look for a ':' before '/', '?', or '#'.
    int schemeColon = -1;
    for (; i < n; ++i) {
      char c = s.charAt(i);
      if (c == ':') {
        schemeColon = i;
        break;
      } else if (c == '/' || c == '?' || c == '#') {
        break;
      }
    }
    if (schemeColon < 0) {
      throw new IllegalArgumentException("Missing required scheme.");
    }
    builder.setRawScheme(s.substring(0, schemeColon));

    // 3.2. Authority. Look for '//' then keep scanning until '/', '?', or '#'.
    i = schemeColon + 1;
    if (i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '/') {
      // "//" just means we have an authority. Skip over it.
      i += 2;

      int authorityStart = i;
      for (; i < n; ++i) {
        char c = s.charAt(i);
        if (c == '/' || c == '?' || c == '#') {
          break;
        }
      }
      String authority = s.substring(authorityStart, i);

      // 3.2.1. UserInfo. Easy, because '@' cannot appear unencoded inside userinfo or host.
      int userInfoEnd = authority.indexOf('@');
      if (userInfoEnd >= 0) {
        builder.setRawUserInfo(authority.substring(0, userInfoEnd));
      }

      // 3.2.2/3. Host/Port.
      int hostStart = userInfoEnd >= 0 ? userInfoEnd + 1 : 0;
      int portStartColon = findPortStartColon(authority, hostStart);
      if (portStartColon < 0) {
        builder.setRawHost(authority.substring(hostStart, authority.length()));
      } else {
        builder.setRawHost(authority.substring(hostStart, portStartColon));
        builder.setRawPort(authority.substring(portStartColon + 1));
      }
    }

    // 3.3. Path: Whatever is left before '?' or '#'.
    int pathStart = i;
    for (; i < n; ++i) {
      char c = s.charAt(i);
      if (c == '?' || c == '#') {
        break;
      }
    }
    builder.setRawPath(s.substring(pathStart, i));

    // 3.4. Query, if we stopped at '?'.
    if (i < n && s.charAt(i) == '?') {
      i++; // Skip '?'
      int queryStart = i;
      for (; i < n; ++i) {
        char c = s.charAt(i);
        if (c == '#') {
          break;
        }
      }
      builder.setRawQuery(s.substring(queryStart, i));
    }

    // 3.5. Fragment, if we stopped at '#'.
    if (i < n && s.charAt(i) == '#') {
      ++i; // Skip '#'
      builder.setRawFragment(s.substring(i));
    }

    return builder.build();
  }

  private static int findPortStartColon(String authority, int hostStart) {
    for (int i = authority.length() - 1; i >= hostStart; --i) {
      char c = authority.charAt(i);
      if (c == ':') {
        return i;
      }
      if (c == ']') {
        // Hit the end of IP-literal. Any further colon is inside it and couldn't indicate a port.
        break;
      }
      if (!DIGIT_CHARS.get(c)) {
        // Found a non-digit, non-colon, non-bracket.
        // This means there is no valid port (e.g. host is "example.com")
        break;
      }
    }
    return -1;
  }

  // Checks a raw path for validity and parses it into segments. Let 'out' be null to just validate.
  private static void parseAssumedUtf8PathIntoSegments(
      String path, ImmutableList.Builder<String> out) {
    // Skip the first slash so it doesn't count as an empty segment at the start.
    // (e.g., "/a" -> ["a"], not ["", "a"])
    int start = path.startsWith("/") ? 1 : 0;

    for (int i = start; i < path.length(); ) {
      int nextSlash = path.indexOf('/', i);
      String segment;
      if (nextSlash >= 0) {
        // Typical segment case (e.g., "foo" in "/foo/bar").
        segment = path.substring(i, nextSlash);
        i = nextSlash + 1;
      } else {
        // Final segment case (e.g., "bar" in "/foo/bar").
        segment = path.substring(i);
        i = path.length();
      }
      if (out != null) {
        out.add(percentDecodeAssumedUtf8(segment));
      } else {
        checkPercentEncodedArg(segment, "path segment", P_CHARS);
      }
    }

    // RFC 3986 says a trailing slash creates a final empty segment.
    // (e.g., "/foo/" -> ["foo", ""])
    if (path.endsWith("/") && out != null) {
      out.add("");
    }
  }

  /** Returns the scheme of this URI. */
  public String getScheme() {
    return scheme;
  }

  /**
   * Returns the percent-decoded "Authority" component of this URI, or null if not present.
   *
   * <p>NB: This method assumes the "host" component was encoded as UTF-8, as mandated by RFC 3986.
   * This method also assumes the "user information" part of authority was encoded as UTF-8,
   * although RFC 3986 doesn't specify an encoding.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawAuthority()}, {@link #percentDecode(CharSequence)}, then decode the bytes for
   * themselves.
   */
  @Nullable
  public String getAuthority() {
    return percentDecodeAssumedUtf8(getRawAuthority());
  }

  private boolean hasAuthority() {
    return host != null;
  }

  /**
   * Returns the "authority" component of this URI in its originally parsed, possibly
   * percent-encoded form.
   */
  @Nullable
  public String getRawAuthority() {
    if (hasAuthority()) {
      StringBuilder sb = new StringBuilder();
      appendAuthority(sb);
      return sb.toString();
    }
    return null;
  }

  private void appendAuthority(StringBuilder sb) {
    if (userInfo != null) {
      sb.append(userInfo).append('@');
    }
    if (host != null) {
      sb.append(host);
    }
    if (port != null) {
      sb.append(':').append(port);
    }
  }

  /**
   * Returns the percent-decoded "User Information" component of this URI, or null if not present.
   *
   * <p>NB: This method *assumes* this component was encoded as UTF-8, although RFC 3986 doesn't
   * specify an encoding.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawUserInfo()}, {@link #percentDecode(CharSequence)}, then decode the bytes for themselves.
   */
  @Nullable
  public String getUserInfo() {
    return percentDecodeAssumedUtf8(userInfo);
  }

  /**
   * Returns the "User Information" component of this URI in its originally parsed, possibly
   * percent-encoded form.
   */
  @Nullable
  public String getRawUserInfo() {
    return userInfo;
  }

  /**
   * Returns the percent-decoded "host" component of this URI, or null if not present.
   *
   * <p>This method assumes the host was encoded as UTF-8, as mandated by RFC 3986.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawHost()}, {@link #percentDecode(CharSequence)}, then decode the bytes for themselves.
   */
  @Nullable
  public String getHost() {
    return percentDecodeAssumedUtf8(host);
  }

  /**
   * Returns the host component of this URI in its originally parsed, possibly percent-encoded form.
   */
  @Nullable
  public String getRawHost() {
    return host;
  }

  /** Returns the "port" component of this URI, or -1 if not present. */
  public int getPort() {
    return port != null ? Integer.parseInt(port) : -1;
  }

  /** Returns the raw port component of this URI in its originally parsed form. */
  @Nullable
  public String getRawPort() {
    return port;
  }

  /**
   * Returns the (possibly empty) percent-decoded "path" component of this URI.
   *
   * <p>NB: This method *assumes* the path was encoded as UTF-8, although RFC 3986 doesn't specify
   * an encoding.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawPath()}, {@link #percentDecode(CharSequence)}, then decode the bytes for themselves.
   *
   * <p>NB: Prefer {@link #getPathSegments()} because this method's decoding is lossy. For example,
   * consider these (different) URIs:
   *
   * <ul>
   *   <li>file:///home%2Ffolder/my%20file
   *   <li>file:///home/folder/my%20file
   * </ul>
   *
   * <p>Calling getPath() on each returns the same string: <code>/home/folder/my file</code>. You
   * can't tell whether the second '/' character is part of the first path segment or separates the
   * first and second path segments. This method only exists to ease migration from {@link
   * java.net.URI}.
   */
  public String getPath() {
    return percentDecodeAssumedUtf8(path);
  }

  /**
   * Returns this URI's path as a list of path segments not including the '/' segment delimiters.
   *
   * <p>Prefer this method over {@link #getPath()} because it preserves the distinction between
   * segment separators and literal '/'s within a path segment.
   *
   * <p>The returned list is immutable.
   */
  public List<String> getPathSegments() {
    // Returned list must be immutable but we intentionally keep guava out of the public API.
    ImmutableList.Builder<String> segmentsBuilder = ImmutableList.builder();
    parseAssumedUtf8PathIntoSegments(path, segmentsBuilder);
    return segmentsBuilder.build();
  }

  /**
   * Returns the path component of this URI in its originally parsed, possibly percent-encoded form.
   */
  public String getRawPath() {
    return path;
  }

  /**
   * Returns the percent-decoded "query" component of this URI, or null if not present.
   *
   * <p>NB: This method assumes the query was encoded as UTF-8, although RFC 3986 doesn't specify an
   * encoding.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawQuery()}, {@link #percentDecode(CharSequence)}, then decode the bytes for themselves.
   */
  @Nullable
  public String getQuery() {
    return percentDecodeAssumedUtf8(query);
  }

  /**
   * Returns the query component of this URI in its originally parsed, possibly percent-encoded
   * form, without any leading '?' character.
   */
  @Nullable
  public String getRawQuery() {
    return query;
  }

  /**
   * Returns the percent-decoded "fragment" component of this URI, or null if not present.
   *
   * <p>NB: This method assumes the fragment was encoded as UTF-8, although RFC 3986 doesn't specify
   * an encoding.
   *
   * <p>Decoding errors are indicated by a {@code '\u005CuFFFD'} unicode replacement character in
   * the output. Callers who want to detect and handle errors in some other way should call {@link
   * #getRawFragment()}, {@link #percentDecode(CharSequence)}, then decode the bytes for themselves.
   */
  @Nullable
  public String getFragment() {
    return percentDecodeAssumedUtf8(fragment);
  }

  /**
   * Returns the fragment component of this URI in its original, possibly percent-encoded form, and
   * without any leading '#' character.
   */
  @Nullable
  public String getRawFragment() {
    return fragment;
  }

  /**
   * {@inheritDoc}
   *
   * <p>If this URI was created by {@link #parse(String)} or {@link #create(String)}, then the
   * returned string will match that original input exactly.
   */
  @Override
  public String toString() {
    // https://datatracker.ietf.org/doc/html/rfc3986#section-5.3
    StringBuilder sb = new StringBuilder();
    sb.append(scheme).append(':');
    if (hasAuthority()) {
      sb.append("//");
      appendAuthority(sb);
    }
    sb.append(path);
    if (query != null) {
      sb.append('?').append(query);
    }
    if (fragment != null) {
      sb.append('#').append(fragment);
    }
    return sb.toString();
  }

  /**
   * Returns true iff this URI has a scheme and an authority/path hierarchy, but no fragment.
   *
   * <p>All instances of {@link Uri} are RFC 3986 URIs, not "relative references", so this method is
   * equivalent to {@code getFragment() == null}. It mostly exists for compatibility with {@link
   * java.net.URI}.
   */
  public boolean isAbsolute() {
    return scheme != null && fragment == null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Two instances of {@link Uri} are equal if and only if they have the same string
   * representation, which RFC 3986 calls "Simple String Comparison" (6.2.1). Callers with a higher
   * layer expectation of equality (e.g. <code>http://some%2Dhost:80/foo/./bar.txt</code> ~= <code>
   * http://some-host/foo/bar.txt</code>) will experience false negatives.
   */
  @Override
  public boolean equals(Object otherObj) {
    if (!(otherObj instanceof Uri)) {
      return false;
    }
    Uri other = (Uri) otherObj;
    return Objects.equals(scheme, other.scheme)
        && Objects.equals(userInfo, other.userInfo)
        && Objects.equals(host, other.host)
        && Objects.equals(port, other.port)
        && Objects.equals(path, other.path)
        && Objects.equals(query, other.query)
        && Objects.equals(fragment, other.fragment);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheme, userInfo, host, port, path, query, fragment);
  }

  /** Returns a new Builder initialized with the fields of this URI. */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /** Builder for {@link Uri}. */
  public static final class Builder {
    private String scheme;
    private String path = "";
    private String query;
    private String fragment;
    private String userInfo;
    private String host;
    private String port;

    /** Creates a new builder with all fields uninitialized or set to their default values. */
    public Builder() {}

    Builder(Uri prototype) {
      this.scheme = prototype.scheme;
      this.userInfo = prototype.userInfo;
      this.host = prototype.host;
      this.port = prototype.port;
      this.path = prototype.path;
      this.query = prototype.query;
      this.fragment = prototype.fragment;
    }

    /**
     * Sets the scheme, e.g. "https", "dns" or "xds".
     *
     * @return this, for fluent building
     * @throws IllegalArgumentException if the scheme is invalid.
     */
    @CanIgnoreReturnValue
    public Builder setScheme(String scheme) {
      return setRawScheme(scheme.toLowerCase(Locale.ROOT));
    }

    @CanIgnoreReturnValue
    Builder setRawScheme(String scheme) {
      if (scheme.isEmpty() || !ALPHA_CHARS.get(scheme.charAt(0))) {
        throw new IllegalArgumentException("Scheme must start with an alphabetic char");
      }
      for (int i = 0; i < scheme.length(); i++) {
        char c = scheme.charAt(i);
        if (!SCHEME_CHARS.get(c)) {
          throw new IllegalArgumentException("Invalid character in scheme at index " + i);
        }
      }
      this.scheme = scheme;
      return this;
    }

    /**
     * Specifies the new URI's path component as a string of zero or more '/' delimited segments.
     *
     * <p>Path segments can consist of any string of codepoints. Codepoints that can't be encoded
     * literally will be percent-encoded for you.
     *
     * <p>If a URI contains an authority component, then the path component must either be empty or
     * begin with a slash ("/") character. If a URI does not contain an authority component, then
     * the path cannot begin with two slash characters ("//").
     *
     * <p>This method interprets all '/' characters in 'path' as segment delimiters. If any of your
     * segments contain literal '/' characters, call {@link #setRawPath(String)} instead.
     *
     * <p>See <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.3">RFC 3986 3.3</a>
     * for more.
     *
     * @param path the new path
     * @return this, for fluent building
     */
    @CanIgnoreReturnValue
    public Builder setPath(String path) {
      checkArgument(path != null, "Path can be empty but not null");
      this.path = percentEncode(path, P_CHARS_AND_SLASH);
      return this;
    }

    /**
     * Specifies the new URI's path component as a string of zero or more '/' delimited segments.
     *
     * <p>Path segments can consist of any string of codepoints but the caller must first percent-
     * encode anything other than RFC 3986's "pchar" character class using UTF-8.
     *
     * <p>If a URI contains an authority component, then the path component must either be empty or
     * begin with a slash ("/") character. If a URI does not contain an authority component, then
     * the path cannot begin with two slash characters ("//").
     *
     * <p>This method interprets all '/' characters in 'path' as segment delimiters. If any of your
     * segments contain literal '/' characters, you must percent-encode them.
     *
     * <p>See <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.3">RFC 3986 3.3</a>
     * for more.
     *
     * @param path the new path, a string consisting of characters from "pchar"
     * @return this, for fluent building
     */
    @CanIgnoreReturnValue
    public Builder setRawPath(String path) {
      parseAssumedUtf8PathIntoSegments(path, null);
      this.path = path;
      return this;
    }

    /**
     * Specifies the query component of the new URI (not including the leading '?').
     *
     * <p>Query can contain any string of codepoints. Codepoints that can't be encoded literally
     * will be percent-encoded for you as UTF-8.
     */
    @CanIgnoreReturnValue
    public Builder setQuery(String query) {
      this.query = percentEncode(query, QUERY_CHARS);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setRawQuery(String query) {
      checkPercentEncodedArg(query, "query", QUERY_CHARS);
      this.query = query;
      return this;
    }

    /**
     * Specifies the fragment component of the new URI (not including the leading '#').
     *
     * <p>The fragment can contain any string of codepoints. Codepoints that can't be encoded
     * literally will be percent-encoded for you as UTF-8.
     */
    @CanIgnoreReturnValue
    public Builder setFragment(String fragment) {
      this.fragment = percentEncode(fragment, FRAGMENT_CHARS);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setRawFragment(String fragment) {
      checkPercentEncodedArg(fragment, "fragment", FRAGMENT_CHARS);
      this.fragment = fragment;
      return this;
    }

    /**
     * Set the "user info" component of the new URI, e.g. "username:password", not including the
     * trailing '@' character.
     *
     * <p>User info can contain any string of codepoints. Codepoints that can't be encoded literally
     * will be percent-encoded for you as UTF-8.
     */
    @CanIgnoreReturnValue
    public Builder setUserInfo(String userInfo) {
      this.userInfo = percentEncode(userInfo, USERINFO_CHARS);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setRawUserInfo(String userInfo) {
      checkPercentEncodedArg(userInfo, "userInfo", USERINFO_CHARS);
      this.userInfo = userInfo;
      return this;
    }

    /**
     * Specifies the "host" component of the new URI in its "registered name" form (usually DNS),
     * e.g. "server.com".
     *
     * <p>The registered name can contain any string of codepoints. Codepoints that can't be encoded
     * literally will be percent-encoded for you as UTF-8.
     */
    @CanIgnoreReturnValue
    public Builder setHost(@Nullable String regName) {
      if (regName != null) {
        regName = regName.toLowerCase(Locale.ROOT);
        regName = percentEncode(regName, REG_NAME_CHARS);
      }
      this.host = regName;
      return this;
    }

    /** Specifies the "host" component of the new URI as an IP address. */
    @CanIgnoreReturnValue
    public Builder setHost(InetAddress addr) {
      this.host = InetAddresses.toUriString(addr);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setRawHost(String host) {
      // IP-literal validation is complicated so we delegate it to Guava. We use this particular
      // method of InetAddresses because it doesn't try to match interfaces on the local machine.
      // (The validity of a URI should be the same no matter which machine does the parsing.)
      // TODO(jdcormie): IPFuture
      if (!InetAddresses.isUriInetAddress(host)) {
        // Must be a "registered name".
        checkPercentEncodedArg(host, "host", REG_NAME_CHARS);
      }
      this.host = host;
      return this;
    }

    /**
     * Specifies the "port" component of the new URI, e.g. "8080".
     *
     * <p>The port can be any non-negative integer. A negative value represents "no port".
     */
    @CanIgnoreReturnValue
    public Builder setPort(int port) {
      this.port = port < 0 ? null : Integer.toString(port);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setRawPort(String port) {
      try {
        Integer.parseInt(port); // Result unused.
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid port", e);
      }
      this.port = port;
      return this;
    }

    /** Builds a new instance of {@link Uri} as specified by the setters. */
    public Uri build() {
      return new Uri(this);
    }
  }

  /**
   * Decodes a string of characters in the range [U+0000, U+007F] to bytes.
   *
   * <p>Each percent-encoded sequence (e.g. "%F0" or "%2a", as defined by RFC 3986 2.1) is decoded
   * to the octet it encodes. Other characters are decoded to their code point's single byte value.
   * A literal % character must be encoded as %25.
   *
   * @throws IllegalArgumentException if 's' contains characters out of range or invalid percent
   *     encoding sequences.
   */
  public static ByteBuffer percentDecode(CharSequence s) {
    // This is large enough because each input character needs *at most* one byte of output.
    ByteBuffer outBuf = ByteBuffer.allocate(s.length());
    percentDecode(s, "input", null, outBuf);
    outBuf.flip();
    return outBuf;
  }

  private static void percentDecode(
      CharSequence s, String what, BitSet allowedChars, ByteBuffer outBuf) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%') {
        if (i + 2 >= s.length()) {
          throw new IllegalArgumentException(
              "Invalid percent-encoding at index " + i + " of " + what + ": " + s);
        }
        int h1 = Character.digit(s.charAt(i + 1), 16);
        int h2 = Character.digit(s.charAt(i + 2), 16);
        if (h1 == -1 || h2 == -1) {
          throw new IllegalArgumentException(
              "Invalid hex digit in " + what + " at index " + i + " of: " + s);
        }
        if (outBuf != null) {
          outBuf.put((byte) (h1 << 4 | h2));
        }
        i += 2;
      } else if (allowedChars == null || allowedChars.get(c)) {
        if (outBuf != null) {
          outBuf.put((byte) c);
        }
      } else {
        throw new IllegalArgumentException("Invalid character in " + what + " at index " + i);
      }
    }
  }

  @Nullable
  private static String percentDecodeAssumedUtf8(@Nullable String s) {
    if (s == null || s.indexOf('%') == -1) {
      return s;
    }

    ByteBuffer utf8Bytes = percentDecode(s);
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
          .decode(utf8Bytes)
          .toString();
    } catch (CharacterCodingException e) {
      throw new VerifyException(e); // Should not happen in REPLACE mode.
    }
  }

  @Nullable
  private static String percentEncode(String s, BitSet allowedCodePoints) {
    if (s == null) {
      return null;
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer utf8Bytes;
    try {
      utf8Bytes = encoder.encode(CharBuffer.wrap(s));
    } catch (MalformedInputException e) {
      throw new IllegalArgumentException("Malformed input", e); // Must be a broken surrogate pair.
    } catch (CharacterCodingException e) {
      throw new VerifyException(e); // Should not happen when encoding to UTF-8.
    }

    StringBuilder sb = new StringBuilder();
    while (utf8Bytes.hasRemaining()) {
      int b = 0xff & utf8Bytes.get();
      if (allowedCodePoints.get(b)) {
        sb.append((char) b);
      } else {
        sb.append('%');
        sb.append(HEX_DIGITS_BY_VAL[(b & 0xF0) >> 4]);
        sb.append(HEX_DIGITS_BY_VAL[b & 0x0F]);
      }
    }
    return sb.toString();
  }

  private static void checkPercentEncodedArg(String s, String what, BitSet allowedChars) {
    percentDecode(s, what, allowedChars, null);
  }

  // See UriTest for how these were computed from the ABNF constants in RFC 3986.
  static final BitSet DIGIT_CHARS = BitSet.valueOf(new long[] {0x3ff000000000000L});
  static final BitSet ALPHA_CHARS = BitSet.valueOf(new long[] {0L, 0x7fffffe07fffffeL});
  // scheme        = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
  static final BitSet SCHEME_CHARS =
      BitSet.valueOf(new long[] {0x3ff680000000000L, 0x7fffffe07fffffeL});
  // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
  static final BitSet UNRESERVED_CHARS =
      BitSet.valueOf(new long[] {0x3ff600000000000L, 0x47fffffe87fffffeL});
  // gen-delims    = ":" / "/" / "?" / "#" / "[" / "]" / "@"
  static final BitSet GEN_DELIMS_CHARS =
      BitSet.valueOf(new long[] {0x8400800800000000L, 0x28000001L});
  // sub-delims    = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
  static final BitSet SUB_DELIMS_CHARS = BitSet.valueOf(new long[] {0x28001fd200000000L});
  // reserved      = gen-delims / sub-delims
  static final BitSet RESERVED_CHARS =
      BitSet.valueOf(new long[] {0xac009fda00000000L, 0x28000001L});
  // reg-name      = *( unreserved / pct-encoded / sub-delims )
  static final BitSet REG_NAME_CHARS =
      BitSet.valueOf(new long[] {0x2bff7fd200000000L, 0x47fffffe87fffffeL});
  // userinfo      = *( unreserved / pct-encoded / sub-delims / ":" )
  static final BitSet USERINFO_CHARS =
      BitSet.valueOf(new long[] {0x2fff7fd200000000L, 0x47fffffe87fffffeL});
  // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
  static final BitSet P_CHARS =
      BitSet.valueOf(new long[] {0x2fff7fd200000000L, 0x47fffffe87ffffffL});
  static final BitSet P_CHARS_AND_SLASH =
      BitSet.valueOf(new long[] {0x2fffffd200000000L, 0x47fffffe87ffffffL});
  //  query         = *( pchar / "/" / "?" )
  static final BitSet QUERY_CHARS =
      BitSet.valueOf(new long[] {0xafffffd200000000L, 0x47fffffe87ffffffL});
  // fragment      = *( pchar / "/" / "?" )
  static final BitSet FRAGMENT_CHARS = QUERY_CHARS;

  private static final char[] HEX_DIGITS_BY_VAL = "0123456789ABCDEF".toCharArray();
}
