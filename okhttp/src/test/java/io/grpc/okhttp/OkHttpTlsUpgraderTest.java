/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc.okhttp;

import static io.grpc.okhttp.OkHttpTlsUpgrader.canonicalizeHost;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.okhttp.internal.Protocol;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link io.grpc.okhttp.OkHttpTlsUpgrader}. */
@RunWith(JUnit4.class)
public class OkHttpTlsUpgraderTest {
  @Test public void upgrade_grpcExp() {
    assertTrue(
        OkHttpTlsUpgrader.TLS_PROTOCOLS.indexOf(Protocol.GRPC_EXP) == -1
            || OkHttpTlsUpgrader.TLS_PROTOCOLS.indexOf(Protocol.GRPC_EXP)
                < OkHttpTlsUpgrader.TLS_PROTOCOLS.indexOf(Protocol.HTTP_2));
  }

  @Test public void upgrade_canonicalizeHost() {
    // IPv4
    assertEquals(canonicalizeHost("127.0.0.1"), "127.0.0.1");
    assertEquals(canonicalizeHost("1.2.3.4"), "1.2.3.4");

    // IPv6
    assertEquals(canonicalizeHost("::1"), "::1");
    assertEquals(canonicalizeHost("[::1]"), "::1");
    assertEquals(canonicalizeHost("2001:db8::1"), "2001:db8::1");
    assertEquals(canonicalizeHost("[2001:db8::1]"), "2001:db8::1");
    assertEquals(canonicalizeHost("::192.168.0.1"), "::192.168.0.1");
    assertEquals(canonicalizeHost("[::192.168.0.1]"), "::192.168.0.1");
    assertEquals(canonicalizeHost("::ffff:192.168.0.1"), "::ffff:192.168.0.1");
    assertEquals(canonicalizeHost("[::ffff:192.168.0.1]"), "::ffff:192.168.0.1");
    assertEquals(canonicalizeHost("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210"),
            "FEDC:BA98:7654:3210:FEDC:BA98:7654:3210");
    assertEquals(canonicalizeHost("[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]"),
            "FEDC:BA98:7654:3210:FEDC:BA98:7654:3210");
    assertEquals(canonicalizeHost("1080:0:0:0:8:800:200C:417A"), "1080:0:0:0:8:800:200C:417A");
    assertEquals(canonicalizeHost("[1080:0:0:0:8:800:200C:417A]"), "1080:0:0:0:8:800:200C:417A");
    assertEquals(canonicalizeHost("1080::8:800:200C:417A"), "1080::8:800:200C:417A");
    assertEquals(canonicalizeHost("[1080::8:800:200C:417A]"), "1080::8:800:200C:417A");
    assertEquals(canonicalizeHost("FF01::101"), "FF01::101");
    assertEquals(canonicalizeHost("[FF01::101]"), "FF01::101");
    assertEquals(canonicalizeHost("0:0:0:0:0:0:13.1.68.3"), "0:0:0:0:0:0:13.1.68.3");
    assertEquals(canonicalizeHost("[0:0:0:0:0:0:13.1.68.3]"), "0:0:0:0:0:0:13.1.68.3");
    assertEquals(canonicalizeHost("0:0:0:0:0:FFFF:129.144.52.38"), "0:0:0:0:0:FFFF:129.144.52.38");
    assertEquals(canonicalizeHost("[0:0:0:0:0:FFFF:129.144.52.38]"), "0:0:0:0:0:FFFF:129.144.52.38");
    assertEquals(canonicalizeHost("::13.1.68.3"), "::13.1.68.3");
    assertEquals(canonicalizeHost("[::13.1.68.3]"), "::13.1.68.3");
    assertEquals(canonicalizeHost("::FFFF:129.144.52.38"), "::FFFF:129.144.52.38");
    assertEquals(canonicalizeHost("[::FFFF:129.144.52.38]"), "::FFFF:129.144.52.38");

    // Hostnames
    assertEquals(canonicalizeHost("go"), "go");
    assertEquals(canonicalizeHost("localhost"), "localhost");
    assertEquals(canonicalizeHost("squareup.com"), "squareup.com");
    assertEquals(canonicalizeHost("www.nintendo.co.jp"), "www.nintendo.co.jp");
  }
}
