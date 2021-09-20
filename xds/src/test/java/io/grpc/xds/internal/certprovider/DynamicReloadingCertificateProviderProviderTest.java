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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.internal.JsonParser;
import io.grpc.internal.TimeProvider;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link DynamicReloadingCertificateProviderProvider}. */
@RunWith(JUnit4.class)
public class DynamicReloadingCertificateProviderProviderTest {

  @Mock DynamicReloadingCertificateProvider.Factory dynamicReloadingCertificateProviderFactory;
  @Mock private DynamicReloadingCertificateProviderProvider.ScheduledExecutorServiceFactory
      scheduledExecutorServiceFactory;
  @Mock private TimeProvider timeProvider;

  private DynamicReloadingCertificateProviderProvider provider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    provider =
        new DynamicReloadingCertificateProviderProvider(
            dynamicReloadingCertificateProviderFactory,
            scheduledExecutorServiceFactory,
            timeProvider);
  }

  @Test
  public void providerRegisteredName() {
    CertificateProviderProvider certProviderProvider =
        CertificateProviderRegistry.getInstance()
            .getProvider(
                DynamicReloadingCertificateProviderProvider.DYNAMIC_RELOADING_PROVIDER_NAME);
    assertThat(certProviderProvider)
        .isInstanceOf(DynamicReloadingCertificateProviderProvider.class);
    DynamicReloadingCertificateProviderProvider dynamicReloadingCertificateProviderProvider =
        (DynamicReloadingCertificateProviderProvider) certProviderProvider;
    assertThat(
            dynamicReloadingCertificateProviderProvider.dynamicReloadingCertificateProviderFactory)
        .isSameInstanceAs(DynamicReloadingCertificateProvider.Factory.getInstance());  
  }

  @Test
  public void createProvider_minimalConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MINIMAL_DYNAMIC_RELOADING_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(dynamicReloadingCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/..data"),
            eq("certificates.pem"),
            eq("private_key.pem"),
            eq("ca_certificates.pem"),
            eq(600L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_fullConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(FULL_DYNAMIC_RELOADING_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(dynamicReloadingCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/..data1"),
            eq("certificates2.pem"),
            eq("private_key3.pem"),
            eq("ca_certificates4.pem"),
            eq(7890L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_missingDir_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_DIR_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'directory' is required in the config");
    }
  }

  @Test
  public void createProvider_missingCert_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_CERT_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'certificate-file' is required in the config");
    }
  }

  @Test
  public void createProvider_missingKey_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_KEY_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'private-key-file' is required in the config");
    }
  }

  @Test
  public void createProvider_missingRoot_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_ROOT_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'ca-certificate-file' is required in the config");
    }
  }

  private static final String MINIMAL_DYNAMIC_RELOADING_CONFIG =
      "{\n"
          + "        \"directory\": \"/var/run/gke-spiffe/certs/..data\","
          + "        \"certificate-file\": \"certificates.pem\","
          + "        \"private-key-file\": \"private_key.pem\","
          + "        \"ca-certificate-file\": \"ca_certificates.pem\""
          + "      }";

  private static final String FULL_DYNAMIC_RELOADING_CONFIG =
      "{\n"
          + "        \"directory\": \"/var/run/gke-spiffe/certs/..data1\","
          + "        \"certificate-file\": \"certificates2.pem\","
          + "        \"private-key-file\": \"private_key3.pem\","
          + "        \"ca-certificate-file\": \"ca_certificates4.pem\","
          + "        \"refresh-interval\": 7890"
          + "      }";

  private static final String MISSING_DIR_CONFIG =
      "{\n"
          + "        \"certificate-file\": \"certificates.pem\","
          + "        \"private-key-file\": \"private_key.pem\","
          + "        \"ca-certificate-file\": \"ca_certificates.pem\""
          + "      }";

  private static final String MISSING_CERT_CONFIG =
      "{\n"
          + "        \"directory\": \"/var/run/gke-spiffe/certs/..data\","
          + "        \"private-key-file\": \"private_key.pem\","
          + "        \"ca-certificate-file\": \"ca_certificates.pem\""
          + "      }";

  private static final String MISSING_KEY_CONFIG =
      "{\n"
          + "        \"directory\": \"/var/run/gke-spiffe/certs/..data\","
          + "        \"certificate-file\": \"certificates.pem\","
          + "        \"ca-certificate-file\": \"ca_certificates.pem\""
          + "      }";

  private static final String MISSING_ROOT_CONFIG =
      "{\n"
          + "        \"directory\": \"/var/run/gke-spiffe/certs/..data\","
          + "        \"certificate-file\": \"certificates.pem\","
          + "        \"private-key-file\": \"private_key.pem\""
          + "      }";
}
