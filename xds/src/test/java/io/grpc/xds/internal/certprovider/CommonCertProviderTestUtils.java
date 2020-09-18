/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.certprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import io.grpc.internal.testing.TestUtils;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.security.KeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommonCertProviderTestUtils {
  private static final Logger logger =
          Logger.getLogger(CommonCertProviderTestUtils.class.getName());

  private static final Pattern KEY_PATTERN = Pattern.compile(
          "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                  "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                  "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
          Pattern.CASE_INSENSITIVE);

  static PrivateKey getPrivateKey(String resourceName)
          throws Exception {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    ByteBuf encodedKeyBuf = readPrivateKey(inputStream);

    byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
    encodedKeyBuf.readBytes(encodedKey).release();
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encodedKey);
    return KeyFactory.getInstance("RSA").generatePrivate(spec);
  }

  static ByteBuf readPrivateKey(InputStream in) throws KeyException {
    String content;
    try {
      content = readContent(in);
    } catch (IOException e) {
      throw new KeyException("failed to read key input stream", e);
    }
    Matcher m = KEY_PATTERN.matcher(content);
    if (!m.find()) {
      throw new KeyException("could not find a PKCS #8 private key in input stream");
    }
    ByteBuf base64 = Unpooled.copiedBuffer(m.group(1), CharsetUtil.US_ASCII);
    ByteBuf der = Base64.decode(base64);
    base64.release();
    return der;
  }

  private static String readContent(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      byte[] buf = new byte[8192];
      for (; ; ) {
        int ret = in.read(buf);
        if (ret < 0) {
          break;
        }
        out.write(buf, 0, ret);
      }
      return out.toString(CharsetUtil.US_ASCII.name());
    } finally {
      safeClose(out);
    }
  }

  private static void safeClose(OutputStream out) {
    try {
      out.close();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to close a stream.", e);
    }
  }

  static X509Certificate getCertFromResourceName(String resourceName)
          throws IOException, CertificateException {
    return CertificateUtils.toX509Certificate(
            new ByteArrayInputStream(getResourceContents(resourceName).getBytes(UTF_8)));
  }

  private static String getResourceContents(String resourceName) throws IOException {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    String text = null;
    try (Reader reader = new InputStreamReader(inputStream, UTF_8)) {
      text = CharStreams.toString(reader);
    }
    return text;
  }
}
