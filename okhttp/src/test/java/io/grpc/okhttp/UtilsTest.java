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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.okhttp.internal.CipherSuite;
import io.grpc.okhttp.internal.ConnectionSpec;
import io.grpc.okhttp.internal.TlsVersion;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link Utils}.
 */
@RunWith(JUnit4.class)
public class UtilsTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void convertSpecRejectsPlaintext() {
    io.grpc.okhttp.internal.ConnectionSpec plaintext =
        io.grpc.okhttp.internal.ConnectionSpec.CLEARTEXT;
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("plaintext ConnectionSpec is not accepted");
    Utils.convertSpec(plaintext);
  }

  @Test
  public void convertSpecKeepsAllData() {
    io.grpc.okhttp.internal.ConnectionSpec squareSpec =
        io.grpc.okhttp.internal.ConnectionSpec.MODERN_TLS;
    ConnectionSpec spec = Utils.convertSpec(squareSpec);

    List<io.grpc.okhttp.internal.TlsVersion> squareTlsVersions = squareSpec.tlsVersions();
    List<TlsVersion> tlsVersions = spec.tlsVersions();
    int versionsSize = squareTlsVersions.size();
    List<io.grpc.okhttp.internal.CipherSuite> squareCipherSuites = squareSpec.cipherSuites();
    List<CipherSuite> cipherSuites = spec.cipherSuites();
    int cipherSuitesSize = squareCipherSuites.size();

    assertTrue(spec.isTls());
    assertTrue(spec.supportsTlsExtensions());
    assertEquals(versionsSize, tlsVersions.size());
    for (int i = 0; i < versionsSize; i++) {
      assertEquals(TlsVersion.forJavaName(squareTlsVersions.get(i).javaName()), tlsVersions.get(i));
    }
    assertEquals(cipherSuitesSize, cipherSuites.size());
    for (int i = 0; i < cipherSuitesSize; i++) {
      assertEquals(CipherSuite.forJavaName(squareCipherSuites.get(i).name()), cipherSuites.get(i));
    }
  }
}
