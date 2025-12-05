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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.net.InetAddresses;
import com.google.common.testing.EqualsTester;
import java.net.URISyntaxException;
import java.util.BitSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class UriTest {

  @Test
  public void parse_allComponents() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://user@host:0443/path?query#fragment");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isEqualTo("user@host:0443");
    assertThat(uri.getUserInfo()).isEqualTo("user");
    assertThat(uri.getPort()).isEqualTo(443);
    assertThat(uri.getRawPort()).isEqualTo("0443");
    assertThat(uri.getPath()).isEqualTo("/path");
    assertThat(uri.getQuery()).isEqualTo("query");
    assertThat(uri.getFragment()).isEqualTo("fragment");
    assertThat(uri.toString()).isEqualTo("scheme://user@host:0443/path?query#fragment");
    assertThat(uri.isAbsolute()).isFalse(); // Has a fragment.
  }

  @Test
  public void parse_noAuthority() throws URISyntaxException {
    Uri uri = Uri.parse("scheme:/path?query#fragment");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isNull();
    assertThat(uri.getPath()).isEqualTo("/path");
    assertThat(uri.getQuery()).isEqualTo("query");
    assertThat(uri.getFragment()).isEqualTo("fragment");
    assertThat(uri.toString()).isEqualTo("scheme:/path?query#fragment");
    assertThat(uri.isAbsolute()).isFalse(); // Has a fragment.
  }

  @Test
  public void parse_ipv6Literal_withPort() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://[2001:db8::7]:012345");
    assertThat(uri.getAuthority()).isEqualTo("[2001:db8::7]:012345");
    assertThat(uri.getRawHost()).isEqualTo("[2001:db8::7]");
    assertThat(uri.getHost()).isEqualTo("[2001:db8::7]");
    assertThat(uri.getRawPort()).isEqualTo("012345");
    assertThat(uri.getPort()).isEqualTo(12345);
  }

  @Test
  public void parse_ipv6Literal_noPort() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://[2001:db8::7]");
    assertThat(uri.getAuthority()).isEqualTo("[2001:db8::7]");
    assertThat(uri.getRawHost()).isEqualTo("[2001:db8::7]");
    assertThat(uri.getHost()).isEqualTo("[2001:db8::7]");
    assertThat(uri.getRawPort()).isNull();
    assertThat(uri.getPort()).isLessThan(0);
  }

  @Test
  public void parse_noQuery() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://authority/path#fragment");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isEqualTo("authority");
    assertThat(uri.getPath()).isEqualTo("/path");
    assertThat(uri.getQuery()).isNull();
    assertThat(uri.getFragment()).isEqualTo("fragment");
    assertThat(uri.toString()).isEqualTo("scheme://authority/path#fragment");
  }

  @Test
  public void parse_noFragment() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://authority/path?query");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isEqualTo("authority");
    assertThat(uri.getPath()).isEqualTo("/path");
    assertThat(uri.getQuery()).isEqualTo("query");
    assertThat(uri.getFragment()).isNull();
    assertThat(uri.toString()).isEqualTo("scheme://authority/path?query");
    assertThat(uri.isAbsolute()).isTrue();
  }

  @Test
  public void parse_emptyPathWithAuthority() throws URISyntaxException {
    Uri uri = Uri.parse("scheme://authority");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isEqualTo("authority");
    assertThat(uri.getPath()).isEmpty();
    assertThat(uri.getQuery()).isNull();
    assertThat(uri.getFragment()).isNull();
    assertThat(uri.toString()).isEqualTo("scheme://authority");
    assertThat(uri.isAbsolute()).isTrue();
  }

  @Test
  public void parse_rootless() throws URISyntaxException {
    Uri uri = Uri.parse("mailto:ceo@company.com?subject=raise");
    assertThat(uri.getScheme()).isEqualTo("mailto");
    assertThat(uri.getAuthority()).isNull();
    assertThat(uri.getPath()).isEqualTo("ceo@company.com");
    assertThat(uri.getQuery()).isEqualTo("subject=raise");
    assertThat(uri.getFragment()).isNull();
    assertThat(uri.toString()).isEqualTo("mailto:ceo@company.com?subject=raise");
    assertThat(uri.isAbsolute()).isTrue();
  }

  @Test
  public void parse_emptyPath() throws URISyntaxException {
    Uri uri = Uri.parse("scheme:");
    assertThat(uri.getScheme()).isEqualTo("scheme");
    assertThat(uri.getAuthority()).isNull();
    assertThat(uri.getPath()).isEmpty();
    assertThat(uri.getQuery()).isNull();
    assertThat(uri.getFragment()).isNull();
    assertThat(uri.toString()).isEqualTo("scheme:");
    assertThat(uri.isAbsolute()).isTrue();
  }

  @Test
  public void parse_invalidScheme_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("1scheme://authority/path"));
    assertThat(e).hasMessageThat().contains("Scheme must start with an alphabetic char");

    e = assertThrows(URISyntaxException.class, () -> Uri.parse(":path"));
    assertThat(e).hasMessageThat().contains("Scheme must start with an alphabetic char");
  }

  @Test
  public void parse_unTerminatedScheme_throws() {
    URISyntaxException e = assertThrows(URISyntaxException.class, () -> Uri.parse("scheme/"));
    assertThat(e).hasMessageThat().contains("Missing required scheme");

    e = assertThrows(URISyntaxException.class, () -> Uri.parse("scheme?"));
    assertThat(e).hasMessageThat().contains("Missing required scheme");

    e = assertThrows(URISyntaxException.class, () -> Uri.parse("scheme#"));
    assertThat(e).hasMessageThat().contains("Missing required scheme");
  }

  @Test
  public void parse_invalidCharactersInScheme_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("schem e://authority/path"));
    assertThat(e).hasMessageThat().contains("Invalid character in scheme");
  }

  @Test
  public void parse_unTerminatedAuthority_throws() {
    Uri uri = Uri.create("s://auth/");
    assertThat(uri.getAuthority()).isEqualTo("auth");
    uri = Uri.create("s://auth?");
    assertThat(uri.getAuthority()).isEqualTo("auth");
    uri = Uri.create("s://auth#");
    assertThat(uri.getAuthority()).isEqualTo("auth");
  }

  @Test
  public void parse_invalidCharactersInUserinfo_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://u ser@host/path"));
    assertThat(e).hasMessageThat().contains("Invalid character in userInfo");
  }

  @Test
  public void parse_invalidBackslashInUserinfo_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("http://other.com\\@intended.com"));
    assertThat(e).hasMessageThat().contains("Invalid character in userInfo");
  }

  @Test
  public void parse_invalidCharactersInHost_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://h ost/path"));
    assertThat(e).hasMessageThat().contains("Invalid character in host");
  }

  @Test
  public void parse_invalidBackslashInHost_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("http://other.com\\.intended.com"));
    assertThat(e).hasMessageThat().contains("Invalid character in host");
  }

  @Test
  public void parse_emptyPort_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://user@host:/path"));
    assertThat(e).hasMessageThat().contains("Invalid port");
  }

  @Test
  public void parse_invalidCharactersInPort_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://user@host:8 0/path"));
    assertThat(e).hasMessageThat().contains("Invalid character");
  }

  @Test
  public void parse_nonAsciiCharacterInPath_throws() throws URISyntaxException {
    URISyntaxException e = assertThrows(URISyntaxException.class, () -> Uri.parse("foo:bär"));
    assertThat(e).hasMessageThat().contains("Invalid character in path");
  }

  @Test
  public void parse_invalidCharactersInPath_throws() {
    URISyntaxException e = assertThrows(URISyntaxException.class, () -> Uri.parse("scheme:/p ath"));
    assertThat(e).hasMessageThat().contains("Invalid character in path");
  }

  @Test
  public void parse_invalidCharactersInQuery_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://user@host/p?q[]uery"));
    assertThat(e).hasMessageThat().contains("Invalid character in query");
  }

  @Test
  public void parse_invalidCharactersInFragment_throws() {
    URISyntaxException e =
        assertThrows(URISyntaxException.class, () -> Uri.parse("scheme://user@host/path#f[]rag"));
    assertThat(e).hasMessageThat().contains("Invalid character in fragment");
  }

  @Test
  public void parse_nonAsciiCharacterInFragment_throws() throws URISyntaxException {
    URISyntaxException e = assertThrows(URISyntaxException.class, () -> Uri.parse("foo:#bär"));
    assertThat(e).hasMessageThat().contains("Invalid character in fragment");
  }

  @Test
  public void parse_decoding() throws URISyntaxException {
    Uri uri = Uri.parse("s://user%2Ename:pass%2Eword@a%2db:1234/p%20ath?q%20uery#f%20ragment");
    assertThat(uri.getAuthority()).isEqualTo("user.name:pass.word@a-b:1234");
    assertThat(uri.getRawAuthority()).isEqualTo("user%2Ename:pass%2Eword@a%2db:1234");
    assertThat(uri.getUserInfo()).isEqualTo("user.name:pass.word");
    assertThat(uri.getRawUserInfo()).isEqualTo("user%2Ename:pass%2Eword");
    assertThat(uri.getHost()).isEqualTo("a-b");
    assertThat(uri.getRawHost()).isEqualTo("a%2db");
    assertThat(uri.getPort()).isEqualTo(1234);
    assertThat(uri.getPath()).isEqualTo("/p ath");
    assertThat(uri.getRawPath()).isEqualTo("/p%20ath");
    assertThat(uri.getQuery()).isEqualTo("q uery");
    assertThat(uri.getRawQuery()).isEqualTo("q%20uery");
    assertThat(uri.getFragment()).isEqualTo("f ragment");
    assertThat(uri.getRawFragment()).isEqualTo("f%20ragment");
  }

  @Test
  public void parse_decodingNonAscii() throws URISyntaxException {
    Uri uri = Uri.parse("s://a/%E2%82%AC");
    assertThat(uri.getPath()).isEqualTo("/€");
  }

  @Test
  public void parse_decodingPercent() throws URISyntaxException {
    Uri uri = Uri.parse("s://a/p%2520ath?q%25uery#f%25ragment");
    assertThat(uri.getPath()).isEqualTo("/p%20ath");
    assertThat(uri.getQuery()).isEqualTo("q%uery");
    assertThat(uri.getFragment()).isEqualTo("f%ragment");
  }

  @Test
  public void parse_invalidPercentEncoding_throws() {
    URISyntaxException e = assertThrows(URISyntaxException.class, () -> Uri.parse("s://a/p%2"));
    assertThat(e).hasMessageThat().contains("Invalid");

    e = assertThrows(URISyntaxException.class, () -> Uri.parse("s://a/p%2G"));
    assertThat(e).hasMessageThat().contains("Invalid");
  }

  @Test
  public void parse_emptyAuthority() {
    Uri uri = Uri.create("file:///foo/bar");
    assertThat(uri.getAuthority()).isEmpty();
    assertThat(uri.getHost()).isEmpty();
    assertThat(uri.getUserInfo()).isNull();
    assertThat(uri.getPort()).isEqualTo(-1);
    assertThat(uri.getPath()).isEqualTo("/foo/bar");
  }

  @Test
  public void parse_pathSegments_empty() throws URISyntaxException {
    Uri uri = Uri.create("scheme:");
    assertThat(uri.getPathSegments()).isEmpty();
  }

  @Test
  public void parse_pathSegments_root() throws URISyntaxException {
    Uri uri = Uri.create("scheme:/");
    assertThat(uri.getPathSegments()).containsExactly("");
  }

  @Test
  public void parse_onePathSegment() throws URISyntaxException {
    Uri uri = Uri.create("file:/foo");
    assertThat(uri.getPathSegments()).containsExactly("foo");
  }

  @Test
  public void parse_onePathSegment_trailingSlash() throws URISyntaxException {
    Uri uri = Uri.create("file:/foo/");
    assertThat(uri.getPathSegments()).containsExactly("foo", "");
  }

  @Test
  public void parse_twoPathSegments() throws URISyntaxException {
    Uri uri = Uri.create("file:/foo/bar");
    assertThat(uri.getPathSegments()).containsExactly("foo", "bar");
  }

  @Test
  public void toString_percentEncoding() throws URISyntaxException {
    Uri uri =
        Uri.newBuilder()
            .setScheme("s")
            .setHost("a b")
            .setPath("/p ath")
            .setQuery("q uery")
            .setFragment("f ragment")
            .build();
    assertThat(uri.toString()).isEqualTo("s://a%20b/p%20ath?q%20uery#f%20ragment");
  }

  @Test
  public void parse_transparentRoundTrip_ipLiteral() {
    Uri uri = Uri.create("http://[2001:dB8::7]:080/%4a%4B%2f%2F?%4c%4D#%4e%4F").toBuilder().build();
    assertThat(uri.toString()).isEqualTo("http://[2001:dB8::7]:080/%4a%4B%2f%2F?%4c%4D#%4e%4F");

    // IPv6 host has non-canonical :: zeros and mixed case hex digits.
    assertThat(uri.getRawHost()).isEqualTo("[2001:dB8::7]");
    assertThat(uri.getHost()).isEqualTo("[2001:dB8::7]");
    assertThat(uri.getRawPort()).isEqualTo("080"); // Leading zeros.
    assertThat(uri.getPort()).isEqualTo(80);
    // Unnecessary and mixed case percent encodings.
    assertThat(uri.getRawPath()).isEqualTo("/%4a%4B%2f%2F");
    assertThat(uri.getPathSegments()).containsExactly("JK//");
    assertThat(uri.getRawQuery()).isEqualTo("%4c%4D");
    assertThat(uri.getQuery()).isEqualTo("LM");
    assertThat(uri.getRawFragment()).isEqualTo("%4e%4F");
    assertThat(uri.getFragment()).isEqualTo("NO");
  }

  @Test
  public void parse_transparentRoundTrip_regName() {
    Uri uri = Uri.create("http://aB%4A%4b:080/%4a%4B%2f%2F?%4c%4D#%4e%4F").toBuilder().build();
    assertThat(uri.toString()).isEqualTo("http://aB%4A%4b:080/%4a%4B%2f%2F?%4c%4D#%4e%4F");

    // Mixed case literal chars and hex digits.
    assertThat(uri.getRawHost()).isEqualTo("aB%4A%4b");
    assertThat(uri.getHost()).isEqualTo("aBJK");
    assertThat(uri.getRawPort()).isEqualTo("080"); // Leading zeros.
    assertThat(uri.getPort()).isEqualTo(80);
    // Unnecessary and mixed case percent encodings.
    assertThat(uri.getRawPath()).isEqualTo("/%4a%4B%2f%2F");
    assertThat(uri.getPathSegments()).containsExactly("JK//");
    assertThat(uri.getRawQuery()).isEqualTo("%4c%4D");
    assertThat(uri.getQuery()).isEqualTo("LM");
    assertThat(uri.getRawFragment()).isEqualTo("%4e%4F");
    assertThat(uri.getFragment()).isEqualTo("NO");
  }

  @Test
  public void builder_numericPort() throws URISyntaxException {
    Uri uri = Uri.newBuilder().setScheme("scheme").setHost("host").setPort(80).build();
    assertThat(uri.toString()).isEqualTo("scheme://host:80");
  }

  @Test
  public void builder_ipv6Literal() throws URISyntaxException {
    Uri uri =
        Uri.newBuilder()
            .setScheme("scheme")
            .setHost(InetAddresses.forString("2001:4860:4860::8844"))
            .build();
    assertThat(uri.toString()).isEqualTo("scheme://[2001:4860:4860::8844]");
  }

  @Test
  public void builder_encodingWithAllowedReservedChars() throws URISyntaxException {
    Uri uri =
        Uri.newBuilder()
            .setScheme("s")
            .setUserInfo("u@")
            .setHost("a[]")
            .setPath("/p:/@")
            .setQuery("q/?")
            .setFragment("f/?")
            .build();
    assertThat(uri.toString()).isEqualTo("s://u%40@a%5B%5D/p:/@?q/?#f/?");
  }

  @Test
  public void builder_percentEncodingNonAscii() throws URISyntaxException {
    Uri uri = Uri.newBuilder().setScheme("s").setHost("a").setPath("/€").build();
    assertThat(uri.toString()).isEqualTo("s://a/%E2%82%AC");
  }

  @Test
  public void builder_percentEncodingLoneHighSurrogate_throws() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Uri.newBuilder().setPath("\uD83D")); // Lone high surrogate.
    assertThat(e.getMessage()).contains("Malformed input");
  }

  @Test
  public void builder_hasAuthority_pathStartsWithSlash_throws() throws URISyntaxException {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Uri.newBuilder().setScheme("s").setHost("a").setPath("path").build());
    assertThat(e.getMessage()).contains("Non-empty path must start with '/'");
  }

  @Test
  public void builder_noAuthority_pathStartsWithDoubleSlash_throws() throws URISyntaxException {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Uri.newBuilder().setScheme("s").setPath("//path").build());
    assertThat(e.getMessage()).contains("Path cannot start with '//'");
  }

  @Test
  public void builder_noScheme_throws() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> Uri.newBuilder().build());
    assertThat(e.getMessage()).contains("Missing required scheme");
  }

  @Test
  public void builder_noHost_hasUserInfo_throws() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> Uri.newBuilder().setScheme("scheme").setUserInfo("user").build());
    assertThat(e.getMessage()).contains("Cannot set userInfo without host");
  }

  @Test
  public void builder_noHost_hasPort_throws() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> Uri.newBuilder().setScheme("scheme").setPort(1234).build());
    assertThat(e.getMessage()).contains("Cannot set port without host");
  }

  @Test
  public void builder_normalizesCaseWhereAppropriate() {
    Uri uri =
        Uri.newBuilder()
            .setScheme("hTtP") // #section-3.1 says producers (Builder) should normalize to lower.
            .setHost("aBc") // #section-3.2.2 says producers (Builder) should normalize to lower.
            .setPath("/CdE") // #section-6.2.2.1 says the rest are assumed to be case-sensitive
            .setQuery("fGh")
            .setFragment("IjK")
            .build();
    assertThat(uri.toString()).isEqualTo("http://abc/CdE?fGh#IjK");
  }

  @Test
  public void builder_normalizesIpv6Literal() {
    Uri uri =
        Uri.newBuilder().setScheme("scheme").setHost(InetAddresses.forString("ABCD::EFAB")).build();
    assertThat(uri.toString()).isEqualTo("scheme://[abcd::efab]");
  }

  @Test
  public void builder_canClearAllOptionalFields() {
    Uri uri =
        Uri.create("http://user@host:80/path?query#fragment").toBuilder()
            .setHost((String) null)
            .setPath("")
            .setUserInfo(null)
            .setPort(-1)
            .setQuery(null)
            .setFragment(null)
            .build();
    assertThat(uri.toString()).isEqualTo("http:");
  }

  @Test
  public void toString_percentEncodingMultiChar() throws URISyntaxException {
    Uri uri =
        Uri.newBuilder()
            .setScheme("s")
            .setHost("a")
            .setPath("/emojis/😊/icon.png") // Smile requires two chars to express in a java String.
            .build();
    assertThat(uri.toString()).isEqualTo("s://a/emojis/%F0%9F%98%8A/icon.png");
  }

  @Test
  public void toString_percentEncodingLiteralPercent() throws URISyntaxException {
    Uri uri =
        Uri.newBuilder()
            .setScheme("s")
            .setHost("a")
            .setPath("/p%20ath")
            .setQuery("q%uery")
            .setFragment("f%ragment")
            .build();
    assertThat(uri.toString()).isEqualTo("s://a/p%2520ath?q%25uery#f%25ragment");
  }

  @Test
  public void equalsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(
            Uri.create("scheme://authority/path?query#fragment"),
            Uri.create("scheme://authority/path?query#fragment"))
        .addEqualityGroup(Uri.create("scheme://authority/path"))
        .addEqualityGroup(Uri.create("scheme://authority/path?query"))
        .addEqualityGroup(Uri.create("scheme:/path"))
        .addEqualityGroup(Uri.create("scheme:/path?query"))
        .addEqualityGroup(Uri.create("scheme:/path#fragment"))
        .addEqualityGroup(Uri.create("scheme:path"))
        .addEqualityGroup(Uri.create("scheme:path?query"))
        .addEqualityGroup(Uri.create("scheme:path#fragment"))
        .addEqualityGroup(Uri.create("scheme:"))
        .testEquals();
  }

  @Test
  public void isAbsolute() {
    assertThat(Uri.create("scheme://authority/path").isAbsolute()).isTrue();
    assertThat(Uri.create("scheme://authority/path?query").isAbsolute()).isTrue();
    assertThat(Uri.create("scheme://authority/path#fragment").isAbsolute()).isFalse();
    assertThat(Uri.create("scheme://authority/path?query#fragment").isAbsolute()).isFalse();
  }

  @Test
  public void serializedCharacterClasses_matchComputed() {
    assertThat(Uri.digitChars).isEqualTo(bitSetOfRange('0', '9'));
    assertThat(Uri.alphaChars).isEqualTo(or(bitSetOfRange('A', 'Z'), bitSetOfRange('a', 'z')));
    assertThat(Uri.schemeChars)
        .isEqualTo(or(Uri.digitChars, Uri.alphaChars, bitSetOf('+', '-', '.')));
    assertThat(Uri.unreservedChars)
        .isEqualTo(or(Uri.alphaChars, Uri.digitChars, bitSetOf('-', '.', '_', '~')));
    assertThat(Uri.genDelimsChars).isEqualTo(bitSetOf(':', '/', '?', '#', '[', ']', '@'));
    assertThat(Uri.subDelimsChars)
        .isEqualTo(bitSetOf('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '='));
    assertThat(Uri.reservedChars).isEqualTo(or(Uri.genDelimsChars, Uri.subDelimsChars));
    assertThat(Uri.regNameChars).isEqualTo(or(Uri.unreservedChars, Uri.subDelimsChars));
    assertThat(Uri.userInfoChars)
        .isEqualTo(or(Uri.unreservedChars, Uri.subDelimsChars, bitSetOf(':')));
    assertThat(Uri.pChars)
        .isEqualTo(or(Uri.unreservedChars, Uri.subDelimsChars, bitSetOf(':', '@')));
    assertThat(Uri.pCharsAndSlash).isEqualTo(or(Uri.pChars, bitSetOf('/')));
    assertThat(Uri.queryChars).isEqualTo(or(Uri.pChars, bitSetOf('/', '?')));
    assertThat(Uri.fragmentChars).isEqualTo(or(Uri.pChars, bitSetOf('/', '?')));
  }

  private static BitSet bitSetOfRange(char from, char to) {
    BitSet bitset = new BitSet();
    for (char c = from; c <= to; c++) {
      bitset.set(c);
    }
    return bitset;
  }

  private static BitSet bitSetOf(char... chars) {
    BitSet bitset = new BitSet();
    for (char c : chars) {
      bitset.set(c);
    }
    return bitset;
  }

  private static BitSet or(BitSet... bitsets) {
    BitSet bitset = new BitSet();
    for (BitSet bs : bitsets) {
      bitset.or(bs);
    }
    return bitset;
  }
}
