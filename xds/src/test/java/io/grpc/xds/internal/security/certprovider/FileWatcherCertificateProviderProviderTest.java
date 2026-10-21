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

package io.grpc.xds.internal.security.certprovider;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.internal.JsonParser;
import io.grpc.internal.TimeProvider;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link FileWatcherCertificateProviderProvider}. */
@RunWith(Parameterized.class)
public class FileWatcherCertificateProviderProviderTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock FileWatcherCertificateProvider.Factory fileWatcherCertificateProviderFactory;
  @Mock private FileWatcherCertificateProviderProvider.ScheduledExecutorServiceFactory
      scheduledExecutorServiceFactory;
  @Mock private TimeProvider timeProvider;

  @Parameter
  public boolean enableSpiffe;
  private boolean originalEnableSpiffe;
  private FileWatcherCertificateProviderProvider provider;

  @Parameters(name = "enableSpiffe={0}")
  public static Collection<Boolean> data() {
    return ImmutableList.of(true, false);
  }

  @Before
  public void setUp() throws IOException {
    provider =
        new FileWatcherCertificateProviderProvider(
            fileWatcherCertificateProviderFactory, scheduledExecutorServiceFactory, timeProvider);
    originalEnableSpiffe = FileWatcherCertificateProviderProvider.enableSpiffe;
    FileWatcherCertificateProviderProvider.enableSpiffe = enableSpiffe;
  }

  @After
  public void restoreEnvironment() {
    FileWatcherCertificateProviderProvider.enableSpiffe = originalEnableSpiffe;
  }

  @Test
  public void providerRegisteredName() {
    CertificateProviderProvider certProviderProvider =
        CertificateProviderRegistry.getInstance()
            .getProvider(FileWatcherCertificateProviderProvider.FILE_WATCHER_PROVIDER_NAME);
    assertThat(certProviderProvider).isInstanceOf(FileWatcherCertificateProviderProvider.class);
    FileWatcherCertificateProviderProvider fileWatcherCertificateProviderProvider =
        (FileWatcherCertificateProviderProvider) certProviderProvider;
    assertThat(fileWatcherCertificateProviderProvider.fileWatcherCertificateProviderFactory)
        .isSameInstanceAs(FileWatcherCertificateProvider.Factory.getInstance());
  }

  @Test
  public void createProvider_minimalConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MINIMAL_FILE_WATCHER_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(fileWatcherCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/certificates.pem"),
            eq("/var/run/gke-spiffe/certs/private_key.pem"),
            eq("/var/run/gke-spiffe/certs/ca_certificates.pem"),
            eq(null),
            eq(600L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_minimalSpiffeConfig() throws IOException {
    Assume.assumeTrue(enableSpiffe);
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MINIMAL_FILE_WATCHER_WITH_SPIFFE_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(fileWatcherCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/certificates.pem"),
            eq("/var/run/gke-spiffe/certs/private_key.pem"),
            eq(null),
            eq("/var/run/gke-spiffe/certs/spiffe_bundle.json"),
            eq(600L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_fullConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(FULL_FILE_WATCHER_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(fileWatcherCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/certificates2.pem"),
            eq("/var/run/gke-spiffe/certs/private_key3.pem"),
            eq("/var/run/gke-spiffe/certs/ca_certificates4.pem"),
            eq(null),
            eq(7890L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_spiffeConfig() throws IOException {
    Assume.assumeTrue(enableSpiffe);
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(FULL_FILE_WATCHER_WITH_SPIFFE_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(fileWatcherCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq("/var/run/gke-spiffe/certs/certificates2.pem"),
            eq("/var/run/gke-spiffe/certs/private_key3.pem"),
            eq(null),
            eq("/var/run/gke-spiffe/certs/spiffe_bundle.json"),
            eq(7890L),
            eq(mockService),
            eq(timeProvider));
  }

  @Test
  public void createProvider_zeroRefreshInterval() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(ZERO_REFRESH_INTERVAL);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create()).thenReturn(mockService);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException iae) {
      assertThat(iae).hasMessageThat().isEqualTo("refreshInterval needs to be greater than 0");
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
      assertThat(npe).hasMessageThat().isEqualTo("'certificate_file' is required in the config");
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
      assertThat(npe).hasMessageThat().isEqualTo("'private_key_file' is required in the config");
    }
  }

  @Test
  public void createProvider_missingRoot_expectException() throws IOException {
    String expectedMessage = enableSpiffe ? "either 'ca_certificate_file' or "
        + "'spiffe_trust_bundle_map_file' is required in the config"
        : "'ca_certificate_file' is required in the config";
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_ROOT_AND_SPIFFE_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo(expectedMessage);
    }
  }

  private static final String MINIMAL_FILE_WATCHER_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates.pem\""
          + "      }";

  private static final String MINIMAL_FILE_WATCHER_WITH_SPIFFE_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key.pem\","
          + "        \"spiffe_trust_bundle_map_file\":"
          + " \"/var/run/gke-spiffe/certs/spiffe_bundle.json\""
          + "      }";

  private static final String FULL_FILE_WATCHER_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates2.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key3.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates4.pem\","
          + "        \"refresh_interval\": \"7890s\""
          + "      }";

  private static final String FULL_FILE_WATCHER_WITH_SPIFFE_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates2.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key3.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates4.pem\","
          + "        \"spiffe_trust_bundle_map_file\":"
          + " \"/var/run/gke-spiffe/certs/spiffe_bundle.json\","
          + "        \"refresh_interval\": \"7890s\""
          + "      }";

  private static final String MISSING_CERT_CONFIG =
      "{\n"
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates.pem\""
          + "      }";

  private static final String MISSING_KEY_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates.pem\""
          + "      }";

  private static final String MISSING_ROOT_AND_SPIFFE_CONFIG =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key.pem\""
          + "      }";

  private static final String ZERO_REFRESH_INTERVAL =
      "{\n"
          + "        \"certificate_file\": \"/var/run/gke-spiffe/certs/certificates2.pem\","
          + "        \"private_key_file\": \"/var/run/gke-spiffe/certs/private_key3.pem\","
          + "        \"ca_certificate_file\": \"/var/run/gke-spiffe/certs/ca_certificates4.pem\","
          + "        \"refresh_interval\": \"0s\""
          + "      }";
}
