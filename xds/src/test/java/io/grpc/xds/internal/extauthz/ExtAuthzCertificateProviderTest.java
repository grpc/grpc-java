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
public class ExtAuthzCertificateProviderTest {
  private final ExtAuthzCertificateProvider provider = ExtAuthzCertificateProvider.create();

  @Test
  public void getPrincipal_ipAddressSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> ipSan = Arrays.asList(7, "192.168.1.1"); // SAN_TYPE_IP_ADDRESS
    Collection<List<?>> sans = Arrays.asList(ipSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("192.168.1.1");
  }

  @Test
  public void getPrincipal_dnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> san = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Collections.singletonList(san);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("foo.test.google.fr");
  }

  @Test
  public void getPrincipal_noSan_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Collections.emptyList());
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getPrincipal_nullSans_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(null);
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getPrincipal_ipSanWrongSize_usesDnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    List<Object> ipSan = Collections.singletonList(7); // SAN_TYPE_IP_ADDRESS, wrong size
    List<Object> dnsSan = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Arrays.asList(ipSan, dnsSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("foo.test.google.fr");
  }

  @Test
  public void getPrincipal_ipSanWrongType_usesDnsSan() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    // SAN_TYPE_IP_ADDRESS, wrong type
    List<Object> ipSan = Arrays.asList("not-an-integer", "192.168.1.1");
    List<Object> dnsSan = Arrays.asList(2, "foo.test.google.fr"); // SAN_TYPE_DNS_NAME
    Collection<List<?>> sans = Arrays.asList(ipSan, dnsSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("foo.test.google.fr");
  }

  @Test
  public void getPrincipal_dnsSanWrongType_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    // Wrong SAN type for DNS check
    List<Object> otherSan = Arrays.asList(6, "foo.test.google.fr"); // SAN_TYPE_URI
    Collection<List<?>> sans = Collections.singletonList(otherSan);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(sans);
    when(mockCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test"));
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("CN=test");
  }

  @Test
  public void getPrincipal_sanParsingException_usesSubject() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getSubjectAlternativeNames()).thenThrow(new CertificateParsingException());
    X500Principal principal = new X500Principal("CN=testclient, O=gRPC authors");
    when(mockCert.getSubjectX500Principal()).thenReturn(principal);
    assertThat(provider.getPrincipal(mockCert)).isEqualTo("CN=testclient,O=gRPC authors");
  }

  @Test
  public void getUrlPemEncodedCertificate() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    byte[] certData = "cert-data".getBytes(StandardCharsets.UTF_8);
    when(mockCert.getEncoded()).thenReturn(certData);

    String pem = "-----BEGIN CERTIFICATE-----\n" + "Y2VydC1kYXRh" // base64 of "cert-data"
        + "\n-----END CERTIFICATE-----\n";
    String urlEncodedPem = URLEncoder.encode(pem, StandardCharsets.UTF_8.toString());
    assertThat(provider.getUrlPemEncodedCertificate(mockCert)).isEqualTo(urlEncodedPem);
  }

  @Test
  public void getUrlPemEncodedCertificate_encodingException() throws Exception {
    X509Certificate mockCert = mock(X509Certificate.class);
    when(mockCert.getEncoded()).thenThrow(new CertificateEncodingException("test"));
    assertThrows(CertificateEncodingException.class,
        () -> provider.getUrlPemEncodedCertificate(mockCert));
  }
}
