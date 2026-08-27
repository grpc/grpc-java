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

package io.grpc.xds.internal.grpcservice;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link HeaderValueValidationUtils}.
 */
@RunWith(JUnit4.class)
public class HeaderValueValidationUtilsTest {

  @Test
  public void isDisallowed_string_emptyKey() {
    assertThat(HeaderValueValidationUtils.isDisallowed("")).isTrue();
  }

  @Test
  public void isDisallowed_string_tooLongKey() {
    String longKey = new String(new char[16385]).replace('\0', 'a');
    assertThat(HeaderValueValidationUtils.isDisallowed(longKey)).isTrue();
  }

  @Test
  public void isDisallowed_string_notLowercase() {
    assertThat(HeaderValueValidationUtils.isDisallowed("Content-Type")).isTrue();
  }

  @Test
  public void isDisallowed_string_grpcPrefix() {
    assertThat(HeaderValueValidationUtils.isDisallowed("grpc-timeout")).isTrue();
  }

  @Test
  public void isDisallowed_string_systemHeader_colon() {
    assertThat(HeaderValueValidationUtils.isDisallowed(":authority")).isTrue();
  }

  @Test
  public void isDisallowed_string_systemHeader_host() {
    assertThat(HeaderValueValidationUtils.isDisallowed("host")).isTrue();
  }

  @Test
  public void isDisallowed_string_valid() {
    assertThat(HeaderValueValidationUtils.isDisallowed("content-type")).isFalse();
  }

  @Test
  public void isDisallowed_headerValue_valid() {
    HeaderValue header = HeaderValue.create("content-type", "application/grpc");
    assertThat(HeaderValueValidationUtils.isDisallowed(header)).isFalse();
  }

  @Test(expected = IllegalArgumentException.class)
  public void isDisallowed_headerValue_invalidAsciiString() {
    HeaderValue.create("content-type", "application/grpc\n");
  }

  @Test(expected = IllegalArgumentException.class)
  public void isDisallowed_headerValue_invalidAsciiRaw() {
    HeaderValue.create("content-type", ByteString.copyFrom(new byte[]{0x61, 0x7F}));
  }

  @Test
  public void isDisallowed_headerValue_validAsciiWithTab() {
    HeaderValue header = HeaderValue.create("content-type", "application/grpc\t");
    assertThat(HeaderValueValidationUtils.isDisallowed(header)).isFalse();
  }

  @Test
  public void isDisallowed_headerValue_binaryHeaderWithArbitraryBytes() {
    // Binary headers ending in -bin can contain any arbitrary bytes (like 0x00, 0x7F, etc.)
    HeaderValue headerString = HeaderValue.create(
        "custom-bin",
        new String(new byte[]{0x00, 0x7F}, java.nio.charset.StandardCharsets.UTF_8));
    assertThat(HeaderValueValidationUtils.isDisallowed(headerString)).isFalse();

    HeaderValue headerRaw = HeaderValue.create(
        "custom-bin", ByteString.copyFrom(new byte[]{0x00, 0x7F}));
    assertThat(HeaderValueValidationUtils.isDisallowed(headerRaw)).isFalse();
  }
}
