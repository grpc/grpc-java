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
import io.grpc.internal.JsonParser;
import io.grpc.internal.TimeProvider;
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
  public static final String EXPECTED_AUDIENCE_V1BETA1_ZONE =
          "identitynamespace:test-project1.svc.id.goog:https://container.googleapis.com/v1beta1/projects/test-project1/zones/test-zone2/clusters/test-cluster3";
  public static final String TMP_PATH_4 = "/tmp/path4";
  public static final String NON_DEFAULT_MESH_CA_URL = "nonDefaultMeshCaUrl";

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
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MINIMAL_MESHCA_CONFIG);
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
  public void createProvider_minimalConfig_v1beta1AndZone()
      throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
        new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(V1BETA1_ZONE_MESHCA_CONFIG);
    ScheduledExecutorService mockService = mock(ScheduledExecutorService.class);
    when(scheduledExecutorServiceFactory.create(
            eq(MeshCaCertificateProviderProvider.MESHCA_URL_DEFAULT)))
        .thenReturn(mockService);
    provider.createCertificateProvider(map, distWatcher, true);
    verify(stsCredentialsFactory, times(1))
        .create(
            eq(MeshCaCertificateProviderProvider.STS_URL_DEFAULT),
            eq(EXPECTED_AUDIENCE_V1BETA1_ZONE),
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
  public void createProvider_missingGkeUrl_expectException()
      throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_GKE_CLUSTER_URL_MESHCA_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'location' is required in the config");
    }
  }

  @Test
  public void createProvider_missingSaJwtLocation_expectException()
      throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MISSING_SAJWT_MESHCA_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("'subject_token_path' is required in the config");
    }
  }

  @Test
  public void createProvider_missingProject_expectException()
      throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(MINIMAL_BAD_CLUSTER_URL_MESHCA_CONFIG);
    try {
      provider.createCertificateProvider(map, distWatcher, true);
      fail("exception expected");
    } catch (IllegalStateException ex) {
      assertThat(ex).hasMessageThat().isEqualTo("gkeClusterUrl does not have correct format");
    }
  }

  @Test
  public void createProvider_badChannelCreds_expectException()
      throws IOException {
    CertificateProvider.DistributorWatcher distWatcher =
            new CertificateProvider.DistributorWatcher();
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(BAD_CHANNEL_CREDS_MESHCA_CONFIG);
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
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(NONDEFAULT_MESHCA_CONFIG);
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

  private static final String NONDEFAULT_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"target_uri\": \"nonDefaultMeshCaUrl\",\n"
          + "              \"channel_credentials\": {\"google_default\": {}},\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"token_exchange_service\": \"test.sts.com\",\n"
          + "                  \"subject_token_path\": \"/tmp/path4\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            },\n" // end google_grpc
          + "            \"time_out\": {\"seconds\": 12}\n"
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"certificate_lifetime\": {\"seconds\": 234567},\n"
          + "        \"renewal_grace_period\": {\"seconds\": 4321},\n"
          + "        \"key_type\": \"RSA\",\n"
          + "        \"key_size\": 512,\n"
          + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  private static final String MINIMAL_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"subject_token_path\": \"/tmp/path5\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            }\n" // end google_grpc
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  private static final String V1BETA1_ZONE_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"subject_token_path\": \"/tmp/path5\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            }\n" // end google_grpc
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"location\": \"https://container.googleapis.com/v1beta1/projects/test-project1/zones/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  private static final String MINIMAL_BAD_CLUSTER_URL_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"subject_token_path\": \"/tmp/path5\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            }\n" // end google_grpc
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  private static final String MISSING_SAJWT_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            }\n" // end google_grpc
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";

  private static final String MISSING_GKE_CLUSTER_URL_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"target_uri\": \"meshca.com\",\n"
          + "              \"channel_credentials\": {\"google_default\": {}},\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
          + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            },\n" // end google_grpc
          + "            \"time_out\": {\"seconds\": 10}\n"
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
          + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
          + "        \"key_type\": \"RSA\",\n"
          + "        \"key_size\": 2048\n"
          + "      }";

  private static final String BAD_CHANNEL_CREDS_MESHCA_CONFIG =
      "{\n"
          + "        \"server\": {\n"
          + "          \"api_type\": \"GRPC\",\n"
          + "          \"grpc_services\": [{\n"
          + "            \"google_grpc\": {\n"
          + "              \"channel_credentials\": {\"mtls\": \"true\"},\n"
          + "              \"call_credentials\": [{\n"
          + "                \"sts_service\": {\n"
          + "                  \"subject_token_path\": \"/tmp/path5\"\n"
          + "                }\n"
          + "              }]\n" // end call_credentials
          + "            }\n" // end google_grpc
          + "          }]\n" // end grpc_services
          + "        },\n" // end server
          + "        \"location\": \"https://container.googleapis.com/v1/projects/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
          + "      }";
}
