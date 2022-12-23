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

package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Charsets;
import io.grpc.internal.testing.TestUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CertificateUtilsTest}. */
@RunWith(JUnit4.class)
public class CertificateUtilsTest {
  public static final String SERVER_0_PEM_FILE = "server0.pem";
  public static final String SERVER_0_KEY_FILE = "server0.key";
  public static final String CA_PEM_FILE = "ca.pem";
  public static final String BAD_PEM_FORMAT = "This is a bad key pem format";
  public static final String BAD_PEM_CONTENT = "----BEGIN PRIVATE KEY-----\n"
      + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDvdzKDTYvRgjBO\n"
      + "-----END PRIVATE KEY-----";
  public static final String ECDSA_KEY_FILE = "ecdsa.key";

  @Test
  public void readPemCertFile() throws CertificateException, IOException {
    InputStream in = TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_PEM_FILE);
    X509Certificate[] cert = CertificateUtils.getX509Certificates(in);
    assertThat(cert.length).isEqualTo(1);
    // Checks some information on the test certificate.
    assertThat(cert[0].getSerialNumber()).isEqualTo(new BigInteger(
        "6c97d344427a93affea089d6855d4ed63dd94f38", 16));
    assertThat(cert[0].getSubjectDN().getName()).isEqualTo(
        "CN=*.test.google.com.au, O=Internet Widgits Pty Ltd, ST=Some-State, C=AU");
  }

  @Test
  public void readPemKeyFile() throws Exception {
    InputStream in = TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_KEY_FILE);
    PrivateKey key = CertificateUtils.getPrivateKey(in);
    // Checks some information on the test key.
    assertThat(key.getAlgorithm()).isEqualTo("RSA");
    assertThat(key.getFormat()).isEqualTo("PKCS#8");
  }

  @Test
  public void readCaPemFile() throws CertificateException, IOException {
    InputStream in = TestUtils.class.getResourceAsStream("/certs/" + CA_PEM_FILE);
    X509Certificate[] cert = CertificateUtils.getX509Certificates(in);
    assertThat(cert.length).isEqualTo(1);
    // Checks some information on the test certificate.
    assertThat(cert[0].getSerialNumber()).isEqualTo(new BigInteger(
        "5ab3f456f1dccbe2cfe94b9836d88bf600610f9a", 16));
    assertThat(cert[0].getSubjectDN().getName()).isEqualTo(
        "CN=testca, O=Internet Widgits Pty Ltd, ST=Some-State, C=AU");
  }

  @Test
  public void readBadFormatKeyFile() throws Exception {
    InputStream in = new ByteArrayInputStream(BAD_PEM_FORMAT.getBytes(Charsets.UTF_8));
    try {
      CertificateUtils.getPrivateKey(in);
      Assert.fail("no exception thrown");
    } catch (InvalidKeySpecException expected) {
      // The error messages for OpenJDK 11 and 8 are different, and for Windows it will generate a
      // different exception, so we only check if a general exception is thrown.
    }
  }

  @Test
  public void readBadContentKeyFile() {
    InputStream in = new ByteArrayInputStream(BAD_PEM_CONTENT.getBytes(Charsets.UTF_8));
    try {
      CertificateUtils.getPrivateKey(in);
      Assert.fail("no exception thrown");
    } catch (Exception expected) {
      // The error messages for OpenJDK 11 and 8 are different, and for Windows it will generate a
      // different exception, so we only check if a general exception is thrown.
    }
  }

  @Test
  public void readEcdsaKeyFile() throws Exception {
    InputStream in = TestUtils.class.getResourceAsStream("/certs/" + ECDSA_KEY_FILE);
    PrivateKey key = CertificateUtils.getPrivateKey(in);
    // Checks some information on the test key.
    assertThat(key.getAlgorithm()).isEqualTo("EC");
    assertThat(key.getFormat()).isEqualTo("PKCS#8");
  }

}
