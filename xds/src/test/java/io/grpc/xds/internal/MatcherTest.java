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

package io.grpc.xds.internal;

import static com.google.common.truth.Truth.assertThat;

import com.google.re2j.Pattern;
import io.grpc.Metadata;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher.Range;
import io.grpc.xds.internal.Matchers.StringMatcher;
import java.net.InetAddress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class MatcherTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Test
  public void testIpMatcher_Ipv4() throws Exception {
    CidrMatcher matcher = CidrMatcher.create("10.10.24.10", 20);
    assertThat(matcher.matches(InetAddress.getByName("::0"))).isFalse();
    assertThat(matcher.matches(InetAddress.getByName("10.10.20.0"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("10.10.16.0"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("10.10.24.10"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("10.10.31.0"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("10.10.17.0"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("10.32.20.0"))).isFalse();
    assertThat(matcher.matches(InetAddress.getByName("10.10.40.0"))).isFalse();
    matcher = CidrMatcher.create("0.0.0.0", 20);
    assertThat(matcher.matches(InetAddress.getByName("10.32.20.0"))).isFalse();
    assertThat(matcher.matches(InetAddress.getByName("0.0.31.0"))).isFalse();
    assertThat(matcher.matches(InetAddress.getByName("0.0.15.0"))).isTrue();
    assertThat(matcher.matches(null)).isFalse();
  }

  @Test
  public void testIpMatcher_Ipv6() throws Exception {
    CidrMatcher matcher = CidrMatcher.create("2012:00fe:d808::", 36);
    assertThat(matcher.matches(InetAddress.getByName("0.0.0.0"))).isFalse();
    assertThat(matcher.matches(InetAddress.getByName("2012:00fe:d000::0"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("2012:00fe:d808::"))).isTrue();
    assertThat(matcher.matches(InetAddress.getByName("2012:00fe:da81:0909:0008:4018:e930:b019")))
        .isTrue();
    assertThat(matcher.matches(InetAddress.getByName("2013:00fe:d000::0"))).isFalse();
  }

  @Test
  public void testStringMatcher() {
    StringMatcher matcher = StringMatcher.forExact("essence", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("ess")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("essential")).isFalse();
    assertThat(matcher.matches("Essence")).isFalse();
    assertThat(matcher.matches("essence")).isTrue();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forExact("essence", true);
    assertThat(matcher.matches("Essence")).isTrue();
    assertThat(matcher.matches("essence")).isTrue();

    matcher = StringMatcher.forPrefix("Ess", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("ess")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("e")).isFalse();
    assertThat(matcher.matches("essential")).isFalse();
    assertThat(matcher.matches("Essence")).isTrue();
    assertThat(matcher.matches("essence")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forPrefix("Ess", true);
    assertThat(matcher.matches("esSEncE")).isTrue();
    assertThat(matcher.matches("ess")).isTrue();
    assertThat(matcher.matches("ES")).isFalse();

    matcher = StringMatcher.forSuffix("ess", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("es")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("ess")).isTrue();
    assertThat(matcher.matches("Excess")).isTrue();
    assertThat(matcher.matches("ExcesS")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forSuffix("ess", true);
    assertThat(matcher.matches("esSEncESs")).isTrue();
    assertThat(matcher.matches("ess")).isTrue();

    matcher = StringMatcher.forContains("ess");
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("es")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("essence")).isTrue();
    assertThat(matcher.matches("eSs")).isFalse();
    assertThat(matcher.matches("ExcesS")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();

    matcher = StringMatcher.forSafeRegEx(Pattern.compile("^es*.*"));
    assertThat(matcher.matches("essence")).isTrue();
  }

  private Metadata metadata(String key, String value) {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return metadata;
  }

  @Test
  public void testHeaderMatcher() {
    HeaderMatcher matcher = HeaderMatcher.forExactValue("version", "v1", false);
    Metadata metadata = metadata("version", "v1");
    assertThat(matcher.matches(metadata)).isTrue();
    metadata = metadata("version", "v2");
    assertThat(matcher.matches(metadata)).isFalse();

    matcher = HeaderMatcher.forExactValue("version", "v1", true);
    metadata = metadata("version", "v1");
    assertThat(matcher.matches(metadata)).isFalse();
    metadata = metadata("version", "v2");
    assertThat(matcher.matches( metadata)).isTrue();

    matcher = HeaderMatcher.forPresent("version", true, false);
    assertThat(matcher.matches(metadata)).isTrue();
    matcher = HeaderMatcher.forPresent("version", false, true);
    metadata.put(Metadata.Key.of("tag", Metadata.ASCII_STRING_MARSHALLER), "todo");
    assertThat(matcher.matches(metadata)).isTrue();
    matcher = HeaderMatcher.forExactValue("versions", "v1", true);
    assertThat(matcher.matches(metadata)).isFalse();

    matcher = HeaderMatcher.forPrefix("version", "v2", false);
    assertThat(matcher.matches(metadata)).isTrue();
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "v2.1");
    assertThat(matcher.matches(metadata)).isTrue();
    matcher = HeaderMatcher.forPrefix("version", "v2", true);
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "v1");
    assertThat(matcher.matches(metadata)).isFalse();
    metadata.put(Metadata.Key.of("version-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[0]);
    assertThat(matcher.matches(metadata)).isFalse();

    matcher = HeaderMatcher.forSuffix("version", "v1", false);
    assertThat(matcher.matches(metadata)).isTrue();
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "v.v2");
    assertThat(matcher.matches(metadata)).isFalse();
    matcher = HeaderMatcher.forSuffix("version", "v2", true);
    assertThat(matcher.matches(metadata)).isFalse();
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "v1");
    assertThat(matcher.matches(metadata)).isTrue();

    matcher = HeaderMatcher.forSafeRegEx("version", Pattern.compile("v2.*"), false);
    assertThat(matcher.matches(metadata)).isTrue();
    metadata = metadata("version", "v1.0");
    assertThat(matcher.matches(metadata)).isFalse();
    matcher = HeaderMatcher.forSafeRegEx("version", Pattern.compile("v1\\..*"), true);
    assertThat(matcher.matches(metadata)).isFalse();
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "v2");
    assertThat(matcher.matches(metadata)).isFalse();
    metadata = metadata("version", "v2");
    assertThat(matcher.matches(metadata)).isTrue();

    matcher = HeaderMatcher.forRange("version", Range.create(8080L, 8090L), false);
    metadata = metadata("version", "8080");
    assertThat(matcher.matches(metadata)).isTrue();
    metadata.put(Metadata.Key.of("version", Metadata.ASCII_STRING_MARSHALLER), "1");
    assertThat(matcher.matches(metadata)).isFalse();
    matcher = HeaderMatcher.forRange("version", Range.create(8080L, 8090L), true);
    assertThat(matcher.matches(metadata)).isTrue();
    metadata = metadata("version", "8080");
    assertThat(matcher.matches(metadata)).isFalse();
  }
}
