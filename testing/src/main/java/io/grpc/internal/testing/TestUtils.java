/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.internal.testing;

import com.google.common.base.Throwables;
import io.grpc.internal.ConscryptLoader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

/**
 * Internal utility functions useful for writing tests.
 */
public class TestUtils {
  public static final String TEST_SERVER_HOST = "foo.test.google.fr";

  /**
   * Creates a new {@link InetSocketAddress} that overrides the host with {@link #TEST_SERVER_HOST}.
   */
  public static InetSocketAddress testServerAddress(String host, int port) {
    try {
      InetAddress inetAddress = InetAddress.getByName(host);
      inetAddress = InetAddress.getByAddress(TEST_SERVER_HOST, inetAddress.getAddress());
      return new InetSocketAddress(inetAddress, port);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a new {@link InetSocketAddress} on localhost that overrides the host with
   * {@link #TEST_SERVER_HOST}.
   */
  public static InetSocketAddress testServerAddress(InetSocketAddress originalSockAddr) {
    try {
      InetAddress inetAddress = InetAddress.getByName("localhost");
      inetAddress = InetAddress.getByAddress(TEST_SERVER_HOST, inetAddress.getAddress());
      return new InetSocketAddress(inetAddress, originalSockAddr.getPort());
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the ciphers preferred to use during tests. They may be chosen because they are widely
   * available or because they are fast. There is no requirement that they provide confidentiality
   * or integrity.
   */
  public static List<String> preferredTestCiphers() {
    String[] ciphers;
    try {
      ciphers = SSLContext.getDefault().getDefaultSSLParameters().getCipherSuites();
    } catch (NoSuchAlgorithmException ex) {
      throw new RuntimeException(ex);
    }
    List<String> ciphersMinusGcm = new ArrayList<>();
    for (String cipher : ciphers) {
      // The GCM implementation in Java is _very_ slow (~1 MB/s)
      if (cipher.contains("_GCM_")) {
        continue;
      }
      ciphersMinusGcm.add(cipher);
    }
    return Collections.unmodifiableList(ciphersMinusGcm);
  }

  /**
   * Saves a file from the classpath resources in src/main/resources/certs as a file on the
   * filesystem.
   *
   * @param name  name of a file in src/main/resources/certs.
   */
  public static File loadCert(String name) throws IOException {
    InputStream
        in = new BufferedInputStream(TestUtils.class.getResourceAsStream("/certs/" + name));
    File tmpFile = File.createTempFile(name, "");
    tmpFile.deleteOnExit();

    OutputStream os = new BufferedOutputStream(new FileOutputStream(tmpFile));
    try {
      int b;
      while ((b = in.read()) != -1) {
        os.write(b);
      }
      os.flush();
    } finally {
      in.close();
      os.close();
    }

    return tmpFile;
  }

  /**
   * Loads an X.509 certificate from the classpath resources in src/main/resources/certs.
   *
   * @param fileName  name of a file in src/main/resources/certs.
   */
  public static X509Certificate loadX509Cert(String fileName)
      throws CertificateException, IOException {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");

    InputStream in = TestUtils.class.getResourceAsStream("/certs/" + fileName);
    try {
      return (X509Certificate) cf.generateCertificate(in);
    } finally {
      in.close();
    }
  }

  private static boolean conscryptInstallAttempted;

  /**
   * Add Conscrypt to the list of security providers, if it is available. If it appears to be
   * available but fails to load, this method will throw an exception. Since the list of security
   * providers is static, this method does nothing if the provider is not available or succeeded
   * previously.
   */
  public static void installConscryptIfAvailable() {
    if (conscryptInstallAttempted) {
      return;
    }
    // Conscrypt-based I/O (like used in OkHttp) breaks on Windows.
    // https://github.com/google/conscrypt/issues/444
    if (System.mapLibraryName("test").endsWith(".dll")) {
      conscryptInstallAttempted = true;
      return;
    }
    if (!ConscryptLoader.isPresent()) {
      conscryptInstallAttempted = true;
      return;
    }
    Provider provider;
    try {
      provider = ConscryptLoader.newProvider();
    } catch (Throwable t) {
      Throwable root = Throwables.getRootCause(t);
      // Conscrypt uses a newer version of glibc than available on RHEL 6
      if (root instanceof UnsatisfiedLinkError && root.getMessage() != null
          && root.getMessage().contains("GLIBC_2.14")) {
        conscryptInstallAttempted = true;
        return;
      }
      throw new RuntimeException("Could not create Conscrypt provider", t);
    }
    Security.addProvider(provider);
    conscryptInstallAttempted = true;
  }

  /**
   * Creates an SSLSocketFactory which contains {@code certChainFile} as its only root certificate.
   */
  public static SSLSocketFactory newSslSocketFactoryForCa(Provider provider,
                                                          File certChainFile) throws Exception {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    BufferedInputStream in = new BufferedInputStream(new FileInputStream(certChainFile));
    try {
      X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
      X500Principal principal = cert.getSubjectX500Principal();
      ks.setCertificateEntry(principal.getName("RFC2253"), cert);
    } finally {
      in.close();
    }

    // Set up trust manager factory to use our key store.
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(ks);
    SSLContext context = SSLContext.getInstance("TLS", provider);
    context.init(null, trustManagerFactory.getTrustManagers(), null);
    return context.getSocketFactory();
  }

  private TestUtils() {}
}
