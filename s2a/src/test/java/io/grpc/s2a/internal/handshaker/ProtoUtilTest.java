/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Expect;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProtoUtil}. */
@RunWith(JUnit4.class)
public final class ProtoUtilTest {
  @Rule public final Expect expect = Expect.create();

  @Test
  public void convertTlsProtocolVersion_success() {
    expect
        .that(ProtoUtil.convertTlsProtocolVersion(TLSVersion.TLS_VERSION_1_3))
        .isEqualTo("TLSv1.3");
    expect
        .that(ProtoUtil.convertTlsProtocolVersion(TLSVersion.TLS_VERSION_1_2))
        .isEqualTo("TLSv1.2");
    expect
        .that(ProtoUtil.convertTlsProtocolVersion(TLSVersion.TLS_VERSION_1_1))
        .isEqualTo("TLSv1.1");
    expect.that(ProtoUtil.convertTlsProtocolVersion(TLSVersion.TLS_VERSION_1_0)).isEqualTo("TLSv1");
  }

  @Test
  public void convertTlsProtocolVersion_withUnknownTlsVersion_fails() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProtoUtil.convertTlsProtocolVersion(TLSVersion.TLS_VERSION_UNSPECIFIED));
    expect.that(expected).hasMessageThat().isEqualTo("TLS version 0 is not supported.");
  }

  @Test
  public void buildTlsProtocolVersionSet_success() {
    expect
        .that(
            ProtoUtil.buildTlsProtocolVersionSet(
                TLSVersion.TLS_VERSION_1_0, TLSVersion.TLS_VERSION_1_3))
        .isEqualTo(ImmutableSet.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"));
    expect
        .that(
            ProtoUtil.buildTlsProtocolVersionSet(
                TLSVersion.TLS_VERSION_1_2, TLSVersion.TLS_VERSION_1_2))
        .isEqualTo(ImmutableSet.of("TLSv1.2"));
    expect
        .that(
            ProtoUtil.buildTlsProtocolVersionSet(
                TLSVersion.TLS_VERSION_1_3, TLSVersion.TLS_VERSION_1_3))
        .isEqualTo(ImmutableSet.of("TLSv1.3"));
    expect
        .that(
            ProtoUtil.buildTlsProtocolVersionSet(
                TLSVersion.TLS_VERSION_1_3, TLSVersion.TLS_VERSION_1_2))
        .isEmpty();
  }

  @Test
  public void buildTlsProtocolVersionSet_failure() {
    expect
        .that(
            ProtoUtil.buildTlsProtocolVersionSet(
                    TLSVersion.TLS_VERSION_UNSPECIFIED, TLSVersion.TLS_VERSION_1_3))
        .isEqualTo(ImmutableSet.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"));
  }
}