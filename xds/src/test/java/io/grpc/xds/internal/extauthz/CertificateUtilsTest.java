/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CertificateUtilsTest {

  @Test
  public void getPrincipal_uriSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> uriSan = Arrays.asList(6, "spiffe://foo/bar"); // SAN_TYPE_URI
    Collection<List<?>> sans = Arrays.asList(uriSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("spiffe://foo/bar");
  }


  @Test
  public void getPrincipal_dnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> san = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Collections.singletonList(san);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("foo.test.google.fr");
  }

  @Test
  public void getPrincipal_noSan_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Collections.emptyList());
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getPrincipal_nullSans_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(null);
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getPrincipal_uriSanWrongSize_usesDnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> uriSan = Collections.singletonList(6); // SAN_TYPE_URI, wrong size
    List<Object> dnsSan = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Arrays.asList(uriSan, dnsSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("foo.test.google.fr");
  }

  @Test
  public void getPrincipal_uriSanTakesPrecedenceOverDnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> uriSan = Arrays.asList(6, "spiffe://foo/bar"); // SAN_TYPE_URI
    List<Object> dnsSan = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Arrays.asList(dnsSan, uriSan); // Order shouldn't matter
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("spiffe://foo/bar");
  }


  @Test
  public void getPrincipal_sanWrongType_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    // Use type 1 (rfc822Name) which is ignored
    List<Object> otherSan = Arrays.asList(1, "foo@test.com");
    Collection<List<?>> sans = Collections.singletonList(otherSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    when(mockCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test"));
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("CN=test");
  }


  @Test
  public void getPrincipal_sanParsingException_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenThrow(new CertificateParsingException());
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(CertificateUtils.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getUrlPemEncodedCertificate() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    byte[] certData = "cert-data".getBytes(StandardCharsets.UTF_8);
    when(mockCert.getEncoded()).thenReturn(certData);

    String pem = "-----BEGIN CERTIFICATE-----\n" + "Y2VydC1kYXRh" // base64 of "cert-data"
        + "\n-----END CERTIFICATE-----\n";
    String urlEncodedPem = URLEncoder.encode(pem, StandardCharsets.UTF_8.toString());
    assertThat(CertificateUtils.getUrlPemEncodedCertificate(mockCert)).isEqualTo(urlEncodedPem);
  }

  @Test
  public void getUrlPemEncodedCertificate_encodingException() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getEncoded()).thenThrow(new CertificateEncodingException("test"));
    assertThrows(CertificateEncodingException.class,
        () -> CertificateUtils.getUrlPemEncodedCertificate(mockCert));
  }
}
