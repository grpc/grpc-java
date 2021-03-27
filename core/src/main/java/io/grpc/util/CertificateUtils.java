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

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CharStreams;
import io.grpc.ExperimentalApi;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains certificate/key PEM file utility method(s).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/XXX")
public final class CertificateUtils {
  private static CertificateFactory factory;
  private static final Pattern KEY_PATTERN = Pattern.compile(
      "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
          "([a-z0-9+/=\\r\\n]+)" +                             // Base64 text
          "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",                  // Footer
      Pattern.CASE_INSENSITIVE);
  private static final BaseEncoding BASE64_ENCODING = BaseEncoding.base64();

  private static synchronized void initInstance() throws CertificateException {
    if (factory == null) {
      factory = CertificateFactory.getInstance("X.509");
    }
  }

  /**
   * Generates X509Certificate array from a file on disk.
   *
   * @param inputStream is a {@link InputStream} from the certificate files
   */
  public static X509Certificate[] getX509Certificates(InputStream inputStream)
      throws CertificateException {
    initInstance();
    Collection<? extends Certificate> certs = factory.generateCertificates(inputStream);
    return certs.toArray(new X509Certificate[0]);
  }

  /** Generates a {@link PrivateKey} from the {@link InputStream}. */
  public static PrivateKey getPrivateKey(InputStream inputStream)
      throws Exception {
    String key = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
    Matcher m = KEY_PATTERN.matcher(key);
    if (!m.find()) {
      throw new KeyException("could not find a PKCS #8 private key in input stream");
    }
    String keyContent = m.group(1).replace("\n", "");
    byte[] decodedKeyBytes = BASE64_ENCODING.decode(keyContent);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKeyBytes);
    return keyFactory.generatePrivate(keySpec);
  }
}

