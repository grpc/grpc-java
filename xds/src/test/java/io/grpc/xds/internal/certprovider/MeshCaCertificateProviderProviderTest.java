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
import static io.grpc.xds.internal.certprovider.MeshCaCertificateProviderProvider.RPC_TIMEOUT_SECONDS;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.auth.oauth2.GoogleCredentials;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.internal.sts.StsCredentials;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link MeshCaCertificateProviderProvider}. */
@RunWith(JUnit4.class)
public class MeshCaCertificateProviderProviderTest {

  public static final String EXPECTED_AUDIENCE =
      "identitynamespace:test-project1.svc.id.goog:https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3";
  public static final String TMP_PATH_4 = "/tmp/path4";
  public static final String NON_DEFAULT_MESH_CA_URL = "nonDefaultMeshCaUrl";
  public static final String GKE_CLUSTER_URL =
      "https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3";

  @Mock
  StsCredentials.Factory stsCredentialsFactory;

  @Mock
  MeshCaCertificateProvider.MeshCaChannelFactory meshCaChannelFactory;

  @Mock
  BackoffPolicy.Provider backoffPolicyProvider;

  @Mock
  MeshCaCertificateProvider.Factory meshCaCertificateProviderFactory;

  @Mock
  private MeshCaCertificateProviderProvider.ScheduledExecutorServiceFactory
      scheduledExecutorServiceFactory;

  @Mock
  private TimeProvider timeProvider;

  private MeshCaCertificateProviderProvider provider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    provider =
        new MeshCaCertificateProviderProvider(
            stsCredentialsFactory,
            meshCaChannelFactory,
            backoffPolicyProvider,
            meshCaCertificateProviderFactory,
            scheduledExecutorServiceFactory,
            timeProvider);
  }

  @Test
  public void providerRegisteredName() {
    CertificateProviderProvider certProviderProvider = CertificateProviderRegistry.getInstance()
        .getProvider(MeshCaCertificateProviderProvider.MESH_CA_NAME);
    assertThat(certProviderProvider).isInstanceOf(MeshCaCertificateProviderProvider.class);
    MeshCaCertificateProviderProvider meshCaCertificateProviderProvider =
        (MeshCaCertificateProviderProvider) certProviderProvider;
    assertThat(meshCaCertificateProviderProvider.stsCredentialsFactory)
        .isSameInstanceAs(StsCredentials.Factory.getInstance());
    assertThat(meshCaCertificateProviderProvider.meshCaChannelFactory)
        .isSameInstanceAs(MeshCaCertificateProvider.MeshCaChannelFactory.getInstance());
    assertThat(meshCaCertificateProviderProvider.backoffPolicyProvider)
        .isInstanceOf(ExponentialBackoffPolicy.Provider.class);
    assertThat(meshCaCertificateProviderProvider.meshCaCertificateProviderFactory)
        .isSameInstanceAs(MeshCaCertificateProvider.Factory.getInstance());
  }

  @Test
  public void createProvider_minimalConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildMinimalConfig();
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create(
            eq(MeshCaCertificateProviderProvider.MESHCA_URL_DEFAULT)))
        .thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(stsCredentialsFactory, times(1))
        .create(
            eq(MeshCaCertificateProviderProvider.STS_URL_DEFAULT),
            eq(EXPECTED_AUDIENCE),
            eq("/tmp/path5"));
    verify(meshCaCertificateProviderFactory, times(1))
        .create(
            eq(distWatcher),
            eq(true),
            eq(MeshCaCertificateProviderProvider.MESHCA_URL_DEFAULT),
            eq("test-zone2"),
            eq(MeshCaCertificateProviderProvider.CERT_VALIDITY_SECONDS_DEFAULT),
            eq(MeshCaCertificateProviderProvider.KEY_SIZE_DEFAULT),
            eq(MeshCaCertificateProviderProvider.KEY_ALGO_DEFAULT),
            eq(MeshCaCertificateProviderProvider.SIGNATURE_ALGO_DEFAULT),
            eq(meshCaChannelFactory),
            eq(backoffPolicyProvider),
            eq(MeshCaCertificateProviderProvider.RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT),
            eq(MeshCaCertificateProviderProvider.MAX_RETRY_ATTEMPTS_DEFAULT),
            (GoogleCredentials) isNull(),
            eq(mockService),
            eq(timeProvider),
            eq(TimeUnit.SECONDS.toMillis(RPC_TIMEOUT_SECONDS)));
  }

  @Test
  public void createProvider_missingGkeUrl_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildMissingGkeClusterUrlConfig();
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'location' is required in the config");
    }
  }

  @Test
  public void createProvider_missingGkeSaJwtLocation_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildMissingSaJwtLocationConfig();
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'subject_token_path' is required in the config");
    }
  }

  @Test
  public void createProvider_missingProject_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildBadClusterUrlConfig();
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (IllegalStateException ex) {
      assertThat(ex).hasMessageThat().isEqualTo("gkeClusterUrl does not have correct format");
    }
  }

  @Test
  public void createProvider_badChannelCreds_expectException() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildBadChannelCredsConfig();
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException ex) {
      assertThat(ex).hasMessageThat().isEqualTo("channel_credentials need to be google_default!");
    }
  }

  @Test
  public void createProvider_nonDefaultFullConfig() throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, ?> map = buildFullConfig();
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create(eq(NON_DEFAULT_MESH_CA_URL)))
        .thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(stsCredentialsFactory, times(1))
            .create(
                    eq("test.sts.com"),
                    eq(EXPECTED_AUDIENCE),
                    eq(TMP_PATH_4));
    verify(meshCaCertificateProviderFactory, times(1))
            .create(
                    eq(distWatcher),
                    eq(true),
                    eq(NON_DEFAULT_MESH_CA_URL),
                    eq("test-zone2"),
                    eq(234567L),
                    eq(512),
                    eq("RSA"),
                    eq("SHA256withRSA"),
                    eq(meshCaChannelFactory),
                    eq(backoffPolicyProvider),
                    eq(4321L),
                    eq(3),
                    (GoogleCredentials) isNull(),
                    eq(mockService),
                    eq(timeProvider),
                    eq(TimeUnit.SECONDS.toMillis(RPC_TIMEOUT_SECONDS)));
  }

  private Map<String, ?> buildFullConfig() throws IOException {
    return getCertProviderConfig(CommonCertProviderTestUtils.getNonDefaultTestBootstrapInfo());
  }

  private Map<String, ?> buildMinimalConfig() throws IOException {
    return getCertProviderConfig(CommonCertProviderTestUtils.getMinimalBootstrapInfo());
  }

  private Map<String, ?> buildBadClusterUrlConfig() throws IOException {
    return getCertProviderConfig(
        CommonCertProviderTestUtils.getMinimalAndBadClusterUrlBootstrapInfo());
  }

  private Map<String, ?> buildMissingSaJwtLocationConfig() throws IOException {
    return getCertProviderConfig(CommonCertProviderTestUtils.getMissingSaJwtLocation());
  }

  private Map<String, ?> buildMissingGkeClusterUrlConfig() throws IOException {
    return getCertProviderConfig(CommonCertProviderTestUtils.getMissingGkeClusterUrl());
  }

  private Map<String, ?> buildBadChannelCredsConfig() throws IOException {
    return getCertProviderConfig(CommonCertProviderTestUtils.getBadChannelCredsConfig());
  }

  private Map<String, ?> getCertProviderConfig(Bootstrapper.BootstrapInfo bootstrapInfo) {
    Map<String, Bootstrapper.CertificateProviderInfo> certProviders =
            bootstrapInfo.getCertProviders();
    Bootstrapper.CertificateProviderInfo gcpIdInfo =
            certProviders.get("gcp_id");
    return gcpIdInfo.getConfig();
  }
}
