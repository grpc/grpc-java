/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Forked from OkHttp 2.7.0 com.squareup.okhttp.HttpUrl
 */
package io.grpc.okhttp.internal.proxy;

import java.io.EOFException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import okio.Buffer;

/**
 * Helper class to build a proxy URL.
 */
public final class HttpUrl {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

  /** Either "http" or "https". */
  private final String scheme;

  /** Canonical hostname. */
  private final String host;

  /** Either 80, 443 or a user-specified port. In range [1..65535]. */
  private final int port;

  /** Canonical URL. */
  private final String url;

  private HttpUrl(Builder builder) {
    this.scheme = builder.scheme;
    this.host = builder.host;
    this.port = builder.effectivePort();
    this.url = builder.toString();
  }

  /** Returns either "http" or "https". */
  public String scheme() {
    return scheme;
  }

  public boolean isHttps() {
    return scheme.equals("https");
  }

  /**
   * Returns the host address suitable for use with {@link InetAddress#getAllByName(String)}. May
   * be:
   * <ul>
   *   <li>A regular host name, like {@code android.com}.
   *   <li>An IPv4 address, like {@code 127.0.0.1}.
   *   <li>An IPv6 address, like {@code ::1}. Note that there are no square braces.
   *   <li>An encoded IDN, like {@code xn--n3h.net}.
   * </ul>
   */
  public String host() {
    return host;
  }

  /**
   * Returns the explicitly-specified port if one was provided, or the default port for this URL's
   * scheme. For example, this returns 8443 for {@code https://square.com:8443/} and 443 for {@code
   * https://square.com/}. The result is in {@code [1..65535]}.
   */
  public int port() {
    return port;
  }

  /**
   * Returns 80 if {@code scheme.equals("http")}, 443 if {@code scheme.equals("https")} and -1
   * otherwise.
   */
  public static int defaultPort(String scheme) {
    if (scheme.equals("http")) {
      return 80;
    } else if (scheme.equals("https")) {
      return 443;
    } else {
      return -1;
    }
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    result.scheme = scheme;
    result.host = host;
    // If we're set to a default port, unset it in case of a scheme change.
    result.port = port != defaultPort(scheme) ? port : -1;
    return result;
  }

  @Override public boolean equals(Object o) {
    return o instanceof HttpUrl && ((HttpUrl) o).url.equals(url);
  }

  @Override public int hashCode() {
    return url.hashCode();
  }

  @Override public String toString() {
    return url;
  }

  public static final class Builder {
    String scheme;
    String host;
    int port = -1;

    public Builder() {
    }

    public Builder scheme(String scheme) {
      if (scheme == null) {
        throw new IllegalArgumentException("scheme == null");
      } else if (scheme.equalsIgnoreCase("http")) {
        this.scheme = "http";
      } else if (scheme.equalsIgnoreCase("https")) {
        this.scheme = "https";
      } else {
        throw new IllegalArgumentException("unexpected scheme: " + scheme);
      }
      return this;
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     *     address.
     */
    public Builder host(String host) {
      if (host == null) throw new IllegalArgumentException("host == null");
      String encoded = canonicalizeHost(host, 0, host.length());
      if (encoded == null) throw new IllegalArgumentException("unexpected host: " + host);
      this.host = encoded;
      return this;
    }

    public Builder port(int port) {
      if (port <= 0 || port > 65535) throw new IllegalArgumentException("unexpected port: " + port);
      this.port = port;
      return this;
    }

    int effectivePort() {
      return port != -1 ? port : defaultPort(scheme);
    }

    public HttpUrl build() {
      if (scheme == null) throw new IllegalStateException("scheme == null");
      if (host == null) throw new IllegalStateException("host == null");
      return new HttpUrl(this);
    }

    @Override public String toString() {
      StringBuilder result = new StringBuilder();
      result.append(scheme);
      result.append("://");

      if (host.indexOf(':') != -1) {
        // Host is an IPv6 address.
        result.append('[');
        result.append(host);
        result.append(']');
      } else {
        result.append(host);
      }

      int effectivePort = effectivePort();
      if (effectivePort != defaultPort(scheme)) {
        result.append(':');
        result.append(effectivePort);
      }

      return result.toString();
    }


    private static String canonicalizeHost(String input, int pos, int limit) {
      // Start by percent decoding the host. The WHATWG spec suggests doing this only after we've
      // checked for IPv6 square braces. But Chrome does it first, and that's more lenient.
      String percentDecoded = percentDecode(input, pos, limit, false);

      // If the input is encased in square braces "[...]", drop 'em. We have an IPv6 address.
      if (percentDecoded.startsWith("[") && percentDecoded.endsWith("]")) {
        InetAddress inetAddress = decodeIpv6(percentDecoded, 1, percentDecoded.length() - 1);
        if (inetAddress == null) return null;
        byte[] address = inetAddress.getAddress();
        if (address.length == 16) return inet6AddressToAscii(address);
        throw new AssertionError();
      }

      return domainToAscii(percentDecoded);
    }

    /** Decodes an IPv6 address like 1111:2222:3333:4444:5555:6666:7777:8888 or ::1. */
    private static InetAddress decodeIpv6(String input, int pos, int limit) {
      byte[] address = new byte[16];
      int b = 0;
      int compress = -1;
      int groupOffset = -1;

      for (int i = pos; i < limit; ) {
        if (b == address.length) return null; // Too many groups.

        // Read a delimiter.
        if (i + 2 <= limit && input.regionMatches(i, "::", 0, 2)) {
          // Compression "::" delimiter, which is anywhere in the input, including its prefix.
          if (compress != -1) return null; // Multiple "::" delimiters.
          i += 2;
          b += 2;
          compress = b;
          if (i == limit) break;
        } else if (b != 0) {
          // Group separator ":" delimiter.
          if (input.regionMatches(i, ":", 0, 1)) {
            i++;
          } else if (input.regionMatches(i, ".", 0, 1)) {
            // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
            if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null;
            b += 2; // We rewound two bytes and then added four.
            break;
          } else {
            return null; // Wrong delimiter.
          }
        }

        // Read a group, one to four hex digits.
        int value = 0;
        groupOffset = i;
        for (; i < limit; i++) {
          char c = input.charAt(i);
          int hexDigit = decodeHexDigit(c);
          if (hexDigit == -1) break;
          value = (value << 4) + hexDigit;
        }
        int groupLength = i - groupOffset;
        if (groupLength == 0 || groupLength > 4) return null; // Group is the wrong size.

        // We've successfully read a group. Assign its value to our byte array.
        address[b++] = (byte) ((value >>> 8) & 0xff);
        address[b++] = (byte) (value & 0xff);
      }

      // All done. If compression happened, we need to move bytes to the right place in the
      // address. Here's a sample:
      //
      //      input: "1111:2222:3333::7777:8888"
      //     before: { 11, 11, 22, 22, 33, 33, 00, 00, 77, 77, 88, 88, 00, 00, 00, 00  }
      //   compress: 6
      //          b: 10
      //      after: { 11, 11, 22, 22, 33, 33, 00, 00, 00, 00, 00, 00, 77, 77, 88, 88 }
      //
      if (b != address.length) {
        if (compress == -1) return null; // Address didn't have compression or enough groups.
        System.arraycopy(address, compress, address, address.length - (b - compress), b - compress);
        Arrays.fill(address, compress, compress + (address.length - b), (byte) 0);
      }

      try {
        return InetAddress.getByAddress(address);
      } catch (UnknownHostException e) {
        throw new AssertionError();
      }
    }

    /** Decodes an IPv4 address suffix of an IPv6 address, like 1111::5555:6666:192.168.0.1. */
    private static boolean decodeIpv4Suffix(
            String input, int pos, int limit, byte[] address, int addressOffset) {
      int b = addressOffset;

      for (int i = pos; i < limit; ) {
        if (b == address.length) return false; // Too many groups.

        // Read a delimiter.
        if (b != addressOffset) {
          if (input.charAt(i) != '.') return false; // Wrong delimiter.
          i++;
        }

        // Read 1 or more decimal digits for a value in 0..255.
        int value = 0;
        int groupOffset = i;
        for (; i < limit; i++) {
          char c = input.charAt(i);
          if (c < '0' || c > '9') break;
          if (value == 0 && groupOffset != i) return false; // Reject unnecessary leading '0's.
          value = (value * 10) + c - '0';
          if (value > 255) return false; // Value out of range.
        }
        int groupLength = i - groupOffset;
        if (groupLength == 0) return false; // No digits.

        // We've successfully read a byte.
        address[b++] = (byte) value;
      }

      if (b != addressOffset + 4) return false; // Too few groups. We wanted exactly four.
      return true; // Success.
    }

    /**
     * Performs IDN ToASCII encoding and canonicalize the result to lowercase. e.g. This converts
     * {@code ☃.net} to {@code xn--n3h.net}, and {@code WwW.GoOgLe.cOm} to {@code www.google.com}.
     * {@code null} will be returned if the input cannot be ToASCII encoded or if the result
     * contains unsupported ASCII characters.
     */
    private static String domainToAscii(String input) {
      try {
        String result = IDN.toASCII(input).toLowerCase(Locale.US);
        if (result.isEmpty()) return null;

        // Confirm that the IDN ToASCII result doesn't contain any illegal characters.
        if (containsInvalidHostnameAsciiCodes(result)) {
          return null;
        }
        // TODO: implement all label limits.
        return result;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static boolean containsInvalidHostnameAsciiCodes(String hostnameAscii) {
      for (int i = 0; i < hostnameAscii.length(); i++) {
        char c = hostnameAscii.charAt(i);
        // The WHATWG Host parsing rules accepts some character codes which are invalid by
        // definition for OkHttp's host header checks (and the WHATWG Host syntax definition). Here
        // we rule out characters that would cause problems in host headers.
        if (c <= '\u001f' || c >= '\u007f') {
          return true;
        }
        // Check for the characters mentioned in the WHATWG Host parsing spec:
        // U+0000, U+0009, U+000A, U+000D, U+0020, "#", "%", "/", ":", "?", "@", "[", "\", and "]"
        // (excluding the characters covered above).
        if (" #%/:?@[\\]".indexOf(c) != -1) {
          return true;
        }
      }
      return false;
    }

    private static String inet6AddressToAscii(byte[] address) {
      // Go through the address looking for the longest run of 0s. Each group is 2-bytes.
      int longestRunOffset = -1;
      int longestRunLength = 0;
      for (int i = 0; i < address.length; i += 2) {
        int currentRunOffset = i;
        while (i < 16 && address[i] == 0 && address[i + 1] == 0) {
          i += 2;
        }
        int currentRunLength = i - currentRunOffset;
        if (currentRunLength > longestRunLength) {
          longestRunOffset = currentRunOffset;
          longestRunLength = currentRunLength;
        }
      }

      // Emit each 2-byte group in hex, separated by ':'. The longest run of zeroes is "::".
      Buffer result = new Buffer();
      for (int i = 0; i < address.length; ) {
        if (i == longestRunOffset) {
          result.writeByte(':');
          i += longestRunLength;
          if (i == 16) result.writeByte(':');
        } else {
          if (i > 0) result.writeByte(':');
          int group = (address[i] & 0xff) << 8 | (address[i + 1] & 0xff);
          result.writeHexadecimalUnsignedLong(group);
          i += 2;
        }
      }
      return result.readUtf8();
    }
  }

  static String percentDecode(String encoded, int pos, int limit, boolean plusIsSpace) {
    for (int i = pos; i < limit; i++) {
      char c = encoded.charAt(i);
      if (c == '%' || (c == '+' && plusIsSpace)) {
        // Slow path: the character at i requires decoding!
        Buffer out = new Buffer();
        out.writeUtf8(encoded, pos, i);
        percentDecode(out, encoded, i, limit, plusIsSpace);
        return out.readUtf8();
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return encoded.substring(pos, limit);
  }

  static void percentDecode(Buffer out, String encoded, int pos, int limit, boolean plusIsSpace) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = encoded.codePointAt(i);
      if (codePoint == '%' && i + 2 < limit) {
        int d1 = decodeHexDigit(encoded.charAt(i + 1));
        int d2 = decodeHexDigit(encoded.charAt(i + 2));
        if (d1 != -1 && d2 != -1) {
          out.writeByte((d1 << 4) + d2);
          i += 2;
          continue;
        }
      } else if (codePoint == '+' && plusIsSpace) {
        out.writeByte(' ');
        continue;
      }
      out.writeUtf8CodePoint(codePoint);
    }
  }

  static int decodeHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  }

  static void canonicalize(Buffer out, String input, int pos, int limit,
                           String encodeSet, boolean alreadyEncoded, boolean plusIsSpace, boolean asciiOnly) {
    Buffer utf8Buffer = null; // Lazily allocated.
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (alreadyEncoded
              && (codePoint == '\t' || codePoint == '\n' || codePoint == '\f' || codePoint == '\r')) {
        // Skip this character.
      } else if (codePoint == '+' && plusIsSpace) {
        // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
        out.writeUtf8(alreadyEncoded ? "+" : "%2B");
      } else if (codePoint < 0x20
              || codePoint == 0x7f
              || (codePoint >= 0x80 && asciiOnly)
              || encodeSet.indexOf(codePoint) != -1
              || (codePoint == '%' && !alreadyEncoded)) {
        // Percent encode this character.
        if (utf8Buffer == null) {
          utf8Buffer = new Buffer();
        }
        utf8Buffer.writeUtf8CodePoint(codePoint);
        while (!utf8Buffer.exhausted()) {
          try {
            fakeEofExceptionMethod(); // Okio 2.x can throw EOFException from readByte()
            int b = utf8Buffer.readByte() & 0xff;
            out.writeByte('%');
            out.writeByte(HEX_DIGITS[(b >> 4) & 0xf]);
            out.writeByte(HEX_DIGITS[b & 0xf]);
          } catch (EOFException e) {
            throw new IndexOutOfBoundsException(e.getMessage());
          }
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        out.writeUtf8CodePoint(codePoint);
      }
    }
  }

  private static void fakeEofExceptionMethod() throws EOFException {}
}
