package io.grpc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

public class CertificateUtils {
  /**
   * Creates a X509ExtendedTrustManager using the provided CA certs if applicable for the
   * certificate type.
   */
  public static Optional<TrustManager> getX509ExtendedTrustManager(InputStream rootCerts)
      throws GeneralSecurityException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    try {
      ks.load(null, null);
    } catch (IOException ex) {
      // Shouldn't really happen, as we're not loading any data.
      throw new GeneralSecurityException(ex);
    }
    X509Certificate[] certs = CertificateUtils.getX509Certificates(rootCerts);
    for (X509Certificate cert : certs) {
      X500Principal principal = cert.getSubjectX500Principal();
      ks.setCertificateEntry(principal.getName("RFC2253"), cert);
    }

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(ks);
    return Arrays.stream(trustManagerFactory.getTrustManagers())
        .filter(trustManager -> trustManager instanceof X509ExtendedTrustManager).findFirst();
  }

  private static X509Certificate[] getX509Certificates(InputStream inputStream)
      throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Collection<? extends Certificate> certs = factory.generateCertificates(inputStream);
    return certs.toArray(new X509Certificate[0]);
  }
}
