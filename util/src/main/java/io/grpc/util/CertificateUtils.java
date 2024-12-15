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

import com.google.common.io.BaseEncoding;
import io.grpc.ExperimentalApi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collection;

/**
 * Contains certificate/key PEM file utility method(s).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
public final class CertificateUtils {
  /**
   * Generates X509Certificate array from a PEM file.
   * The PEM file should contain one or more items in Base64 encoding, each with
   * plain-text headers and footers
   * (e.g. -----BEGIN CERTIFICATE----- and -----END CERTIFICATE-----).
   *
   * @param inputStream is a {@link InputStream} from the certificate files
   */
  public static X509Certificate[] getX509Certificates(InputStream inputStream)
      throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Collection<? extends Certificate> certs = factory.generateCertificates(inputStream);
    return certs.toArray(new X509Certificate[0]);
  }

  /**
   * Generates a {@link PrivateKey} from a PEM file.
   * The key should be PKCS #8 formatted. The key algorithm should be "RSA" or "EC".
   * The PEM file should contain one item in Base64 encoding, with plain-text headers and footers
   * (e.g. -----BEGIN PRIVATE KEY----- and -----END PRIVATE KEY-----).
   *
   * @param inputStream is a {@link InputStream} from the private key file
   */
  public static PrivateKey getPrivateKey(InputStream inputStream)
      throws UnsupportedEncodingException, IOException, NoSuchAlgorithmException,
      InvalidKeySpecException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
    String line;
    while ((line = reader.readLine()) != null) {
      if ("-----BEGIN PRIVATE KEY-----".equals(line)) {
        break;
      }
    }
    StringBuilder keyContent = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      if ("-----END PRIVATE KEY-----".equals(line)) {
        break;
      }
      keyContent.append(line);
    }
    byte[] decodedKeyBytes = BaseEncoding.base64().decode(keyContent.toString());
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKeyBytes);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    } catch (InvalidKeySpecException ignore) {
      try {
        return KeyFactory.getInstance("EC").generatePrivate(keySpec);
      } catch (InvalidKeySpecException e) {
        throw new InvalidKeySpecException("Neither RSA nor EC worked", e);
      }
    }
  }
}

