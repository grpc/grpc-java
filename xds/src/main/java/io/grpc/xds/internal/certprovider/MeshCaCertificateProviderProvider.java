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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.xds.internal.sts.StsCredentials;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider of {@link CertificateProvider}s. Implemented by the implementer of the plugin. We may
 * move this out of the internal package and make this an official API in the future.
 */
final class MeshCaCertificateProviderProvider implements CertificateProviderProvider {

  private static final String MESHCA_URL_KEY = "meshCaUrl";
  private static final String RPC_TIMEOUT_SECONDS_KEY = "rpcTimeoutSeconds";
  private static final String GKECLUSTER_URL_KEY = "gkeClusterUrl";
  private static final String CERT_VALIDITY_SECONDS_KEY = "certValiditySeconds";
  private static final String RENEWAL_GRACE_PERIOD_SECONDS_KEY = "renewalGracePeriodSeconds";
  private static final String KEY_ALGO_KEY = "keyAlgo";  // aka keyType
  private static final String KEY_SIZE_KEY = "keySize";
  private static final String SIGNATURE_ALGO_KEY = "signatureAlgo";
  private static final String MAX_RETRY_ATTEMPTS_KEY = "maxRetryAttempts";
  private static final String STS_URL_KEY = "stsUrl";
  private static final String GKE_SA_JWT_LOCATION_KEY = "gkeSaJwtLocation";

  static final String MESHCA_URL_DEFAULT = "meshca.googleapis.com";
  static final long RPC_TIMEOUT_SECONDS_DEFAULT = 5L;
  static final long CERT_VALIDITY_SECONDS_DEFAULT = 9L * 3600L; // 9 hours
  static final long RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT = 1L * 3600L; // 1 hour
  static final String KEY_ALGO_DEFAULT = "RSA";  // aka keyType
  static final int KEY_SIZE_DEFAULT = 2048;
  static final String SIGNATURE_ALGO_DEFAULT = "SHA256withRSA";
  static final int MAX_RETRY_ATTEMPTS_DEFAULT = 3;
  static final String STS_URL_DEFAULT = "https://securetoken.googleapis.com/v1/identitybindingtoken";

  private static final Pattern CLUSTER_URL_PATTERN = Pattern
      .compile(".*/projects/(.*)/locations/(.*)/clusters/.*");

  private static final String TRUST_DOMAIN_SUFFIX = ".svc.id.goog";
  private static final String AUDIENCE_PREFIX = "identitynamespace:";
  static final String MESH_CA_NAME = "meshCA";

  static {
    CertificateProviderRegistry.getInstance()
        .register(
            new MeshCaCertificateProviderProvider(
                StsCredentials.Factory.getInstance(),
                MeshCaCertificateProvider.MeshCaChannelFactory.getInstance(),
                new ExponentialBackoffPolicy.Provider(),
                MeshCaCertificateProvider.Factory.getInstance()));
  }

  final StsCredentials.Factory stsCredentialsFactory;
  final MeshCaCertificateProvider.MeshCaChannelFactory meshCaChannelFactory;
  final BackoffPolicy.Provider backoffPolicyProvider;
  final MeshCaCertificateProvider.Factory meshCaCertificateProviderFactory;

  @VisibleForTesting
  MeshCaCertificateProviderProvider(StsCredentials.Factory stsCredentialsFactory,
      MeshCaCertificateProvider.MeshCaChannelFactory meshCaChannelFactory,
      BackoffPolicy.Provider backoffPolicyProvider,
      MeshCaCertificateProvider.Factory meshCaCertificateProviderFactory) {
    this.stsCredentialsFactory = stsCredentialsFactory;
    this.meshCaChannelFactory = meshCaChannelFactory;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.meshCaCertificateProviderFactory = meshCaCertificateProviderFactory;
  }

  @Override
  public String getName() {
    return MESH_CA_NAME;
  }

  @Override
  public CertificateProvider createCertificateProvider(
      Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates) {

    Config configObj = validateAndTranslateConfig(config);

    // Construct audience from project and gkeClusterUrl
    String audience =
        AUDIENCE_PREFIX + configObj.project + TRUST_DOMAIN_SUFFIX + ":" + configObj.gkeClusterUrl;
    StsCredentials stsCredentials = stsCredentialsFactory
        .create(configObj.stsUrl, audience, configObj.gkeSaJwtLocation);

    return meshCaCertificateProviderFactory.create(watcher, notifyCertUpdates, configObj.meshCaUrl,
        configObj.zone,
        configObj.certValiditySeconds, configObj.keySize, configObj.keyAlgo,
        configObj.signatureAlgo,
        meshCaChannelFactory, backoffPolicyProvider,
        configObj.renewalGracePeriodSeconds, configObj.maxRetryAttempts, stsCredentials);
  }

  private static Config validateAndTranslateConfig(Object config) {
    // TODO(sanjaypujare): add support for string, struct proto etc
    checkArgument(config instanceof Map, "Only Map supported for config");
    @SuppressWarnings("unchecked") Map<String, String> map = (Map<String, String>)config;

    Config configObj = new Config();
    configObj.meshCaUrl = mapGetOrDefault(map, MESHCA_URL_KEY, MESHCA_URL_DEFAULT);
    configObj.rpcTimeoutSeconds =
        mapGetOrDefault(map, RPC_TIMEOUT_SECONDS_KEY, RPC_TIMEOUT_SECONDS_DEFAULT);
    configObj.gkeClusterUrl =
        checkNotNull(
            map.get(GKECLUSTER_URL_KEY), GKECLUSTER_URL_KEY + " is required in the config");
    configObj.certValiditySeconds =
        mapGetOrDefault(map, CERT_VALIDITY_SECONDS_KEY, CERT_VALIDITY_SECONDS_DEFAULT);
    configObj.renewalGracePeriodSeconds =
        mapGetOrDefault(
            map, RENEWAL_GRACE_PERIOD_SECONDS_KEY, RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT);
    configObj.keyAlgo = mapGetOrDefault(map, KEY_ALGO_KEY, KEY_ALGO_DEFAULT);
    configObj.keySize = mapGetOrDefault(map, KEY_SIZE_KEY, KEY_SIZE_DEFAULT);
    configObj.signatureAlgo = mapGetOrDefault(map, SIGNATURE_ALGO_KEY, SIGNATURE_ALGO_DEFAULT);
    configObj.maxRetryAttempts =
        mapGetOrDefault(map, MAX_RETRY_ATTEMPTS_KEY, MAX_RETRY_ATTEMPTS_DEFAULT);
    configObj.stsUrl = mapGetOrDefault(map, STS_URL_KEY, STS_URL_DEFAULT);
    configObj.gkeSaJwtLocation =
        checkNotNull(
            map.get(GKE_SA_JWT_LOCATION_KEY),
            GKE_SA_JWT_LOCATION_KEY + " is required in the config");
    parseProjectAndZone(configObj.gkeClusterUrl, configObj);
    return configObj;
  }

  private static String mapGetOrDefault(Map<String, String> map, String key, String defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return value;
  }

  private static Long mapGetOrDefault(Map<String, String> map, String key, long defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return Long.parseLong(value);
  }

  private static Integer mapGetOrDefault(Map<String, String> map, String key, int defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return Integer.parseInt(value);
  }

  private static void parseProjectAndZone(String gkeClusterUrl, Config configObj) {
    Matcher matcher = CLUSTER_URL_PATTERN.matcher(gkeClusterUrl);
    checkState(matcher.find(), "gkeClusterUrl does not have correct format");
    checkState(matcher.groupCount() == 2, "gkeClusterUrl does not have project and location parts");
    configObj.project = matcher.group(1);
    configObj.zone = matcher.group(2);
  }

  /** POJO class for storing various config values. */
  @VisibleForTesting
  static class Config {
    String meshCaUrl;
    Long rpcTimeoutSeconds;
    String gkeClusterUrl;
    Long certValiditySeconds;
    Long renewalGracePeriodSeconds;
    String keyAlgo;   // aka keyType
    Integer keySize;
    String signatureAlgo;
    Integer maxRetryAttempts;
    String stsUrl;
    String gkeSaJwtLocation;
    String zone;
    String project;
  }
}
