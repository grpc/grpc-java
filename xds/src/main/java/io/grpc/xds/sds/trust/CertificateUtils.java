/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds.trust;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains various utility methods.
 */
final class CertificateUtils {

  private static final Pattern CERT_PATTERN =
      Pattern.compile(
          "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+"
              + // Header
              "([a-z0-9+/=\\r\\n]+)"
              + // Base64 text
              "-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
          Pattern.CASE_INSENSITIVE);

  private static X509Certificate[] getCertificatesFromBuffers(ByteBuf[] certs)
      throws CertificateException {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    X509Certificate[] x509Certs = new X509Certificate[certs.length];

    try {
      for (int i = 0; i < certs.length; i++) {
        InputStream is = new ByteBufInputStream(certs[i], false);
        try {
          x509Certs[i] = (X509Certificate) cf.generateCertificate(is);
        } finally {
          try {
            is.close();
          } catch (IOException e) {
            // This is not expected to happen, but re-throw in case it does.
            throw new RuntimeException(e);
          }
        }
      }
    } finally {
      for (ByteBuf buf : certs) {
        buf.release();
      }
    }
    return x509Certs;
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
      System.out.println("Failed to close a stream." + e);
    }
  }

  static ByteBuf[] readCertificates(InputStream in) throws CertificateException {
    String content;
    try {
      content = readContent(in);
    } catch (IOException e) {
      throw new CertificateException("failed to read certificate input stream", e);
    }

    List<ByteBuf> certs = new ArrayList<ByteBuf>();
    Matcher m = CERT_PATTERN.matcher(content);
    int start = 0;
    for (; ; ) {
      if (!m.find(start)) {
        break;
      }

      ByteBuf base64 = Unpooled.copiedBuffer(m.group(1), CharsetUtil.US_ASCII);
      ByteBuf der = Base64.decode(base64);
      base64.release();
      certs.add(der);

      start = m.end();
    }

    if (certs.isEmpty()) {
      throw new CertificateException("found no certificates in input stream");
    }

    return certs.toArray(new ByteBuf[0]);
  }

  static X509Certificate[] toX509Certificates(String file)
      throws CertificateException, FileNotFoundException {
    if (file == null) {
      return null;
    }
    return getCertificatesFromBuffers(readCertificates(new FileInputStream(file)));
  }
}
