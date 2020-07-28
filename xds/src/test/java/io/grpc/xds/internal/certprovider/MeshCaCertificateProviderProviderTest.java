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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.auth.oauth2.GoogleCredentials;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.xds.internal.sts.StsCredentials;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
  private MeshCaCertificateProviderProvider provider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    provider =
        new MeshCaCertificateProviderProvider(
            stsCredentialsFactory,
            meshCaChannelFactory,
            backoffPolicyProvider,
            meshCaCertificateProviderFactory);
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
  public void createProvider_minimalConfig() {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    Map<String, String> map = buildMinimalMap();
    provider.createCertificateProvider(map, distWatcher, true);
    verify(stsCredentialsFactory, times(1))
        .create(
            eq(MeshCaCertificateProviderProvider.STS_URL_DEFAULT),
            eq(EXPECTED_AUDIENCE),
            eq(TMP_PATH_4));
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
            (GoogleCredentials) isNull());
  }

  @Test
  public void createProvider_missingGkeUrl_expectException() {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, String> map = buildMinimalMap();
    map.remove("gkeClusterUrl");
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("gkeClusterUrl is required in the config");
    }
  }

  @Test
  public void createProvider_missingGkeSaJwtLocation_expectException() {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, String> map = buildMinimalMap();
    map.remove("gkeSaJwtLocation");
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("gkeSaJwtLocation is required in the config");
    }
  }

  @Test
  public void createProvider_missingProject_expectException() {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, String> map = buildMinimalMap();
    map.put("gkeClusterUrl", "https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3");
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (IllegalStateException ex) {
      assertThat(ex).hasMessageThat().isEqualTo("gkeClusterUrl does not have correct format");
    }
  }

  @Test
  public void createProvider_nonDefaultFullConfig() {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    Map<String, String> map = buildFullMap();
    provider.createCertificateProvider(map, distWatcher, true);
    verify(stsCredentialsFactory, times(1))
            .create(
                    eq("nonDefaultStsUrl"),
                    eq(EXPECTED_AUDIENCE),
                    eq(TMP_PATH_4));
    verify(meshCaCertificateProviderFactory, times(1))
            .create(
                    eq(distWatcher),
                    eq(true),
                    eq(NON_DEFAULT_MESH_CA_URL),
                    eq("test-zone2"),
                    eq(234567L),
                    eq(4096),
                    eq("KEY-ALGO1"),
                    eq("SIG-ALGO2"),
                    eq(meshCaChannelFactory),
                    eq(backoffPolicyProvider),
                    eq(4321L),
                    eq(9),
                    (GoogleCredentials) isNull());
  }

  private Map<String, String> buildFullMap() {
    Map<String, String> map = new HashMap<>();
    map.put("gkeClusterUrl", GKE_CLUSTER_URL);
    map.put("gkeSaJwtLocation", TMP_PATH_4);
    map.put("meshCaUrl", NON_DEFAULT_MESH_CA_URL);
    map.put("rpcTimeoutSeconds", "123");
    map.put("certValiditySeconds", "234567");
    map.put("renewalGracePeriodSeconds", "4321");
    map.put("keyAlgo", "KEY-ALGO1");
    map.put("keySize", "4096");
    map.put("signatureAlgo", "SIG-ALGO2");
    map.put("maxRetryAttempts", "9");
    map.put("stsUrl", "nonDefaultStsUrl");
    return map;
  }

  private Map<String, String> buildMinimalMap() {
    Map<String, String> map = new HashMap<>();
    map.put("gkeClusterUrl", GKE_CLUSTER_URL);
    map.put("gkeSaJwtLocation", TMP_PATH_4);
    return map;
  }
}
