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
import static io.grpc.internal.JsonUtil.getObject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.internal.sts.StsCredentials;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider of {@link CertificateProvider}s. Implemented by the implementer of the plugin. We may
 * move this out of the internal package and make this an official API in the future.
 */
final class MeshCaCertificateProviderProvider implements CertificateProviderProvider {

  private static final String SERVER_CONFIG_KEY = "server";
  private static final String MESHCA_URL_KEY = "target_uri";
  private static final String RPC_TIMEOUT_SECONDS_KEY = "time_out";
  private static final String GKECLUSTER_URL_KEY = "location";
  private static final String CERT_VALIDITY_SECONDS_KEY = "certificate_lifetime";
  private static final String RENEWAL_GRACE_PERIOD_SECONDS_KEY = "renewal_grace_period";
  private static final String KEY_ALGO_KEY = "key_type";  // aka keyType
  private static final String KEY_SIZE_KEY = "key_size";
  private static final String STS_SERVICE_KEY = "sts_service";
  private static final String TOKEN_EXCHANGE_SERVICE_KEY = "token_exchange_service";
  private static final String GKE_SA_JWT_LOCATION_KEY = "subject_token_path";

  @VisibleForTesting static final String MESHCA_URL_DEFAULT = "meshca.googleapis.com";
  @VisibleForTesting static final long RPC_TIMEOUT_SECONDS_DEFAULT = 5L;
  @VisibleForTesting static final long CERT_VALIDITY_SECONDS_DEFAULT = 9L * 3600L;
  @VisibleForTesting static final long RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT = 1L * 3600L;
  @VisibleForTesting static final String KEY_ALGO_DEFAULT = "RSA";  // aka keyType
  @VisibleForTesting static final int KEY_SIZE_DEFAULT = 2048;
  @VisibleForTesting static final String SIGNATURE_ALGO_DEFAULT = "SHA256withRSA";
  @VisibleForTesting static final int MAX_RETRY_ATTEMPTS_DEFAULT = 3;
  @VisibleForTesting
  static final String STS_URL_DEFAULT = "https://securetoken.googleapis.com/v1/identitybindingtoken";

  @VisibleForTesting
  static final long RPC_TIMEOUT_SECONDS = 10L;

  private static final Pattern CLUSTER_URL_PATTERN = Pattern
      .compile(".*/projects/(.*)/(?:locations|zones)/(.*)/clusters/.*");

  private static final String TRUST_DOMAIN_SUFFIX = ".svc.id.goog";
  private static final String AUDIENCE_PREFIX = "identitynamespace:";
  static final String MESH_CA_NAME = "meshCA";

  final StsCredentials.Factory stsCredentialsFactory;
  final MeshCaCertificateProvider.MeshCaChannelFactory meshCaChannelFactory;
  final BackoffPolicy.Provider backoffPolicyProvider;
  final MeshCaCertificateProvider.Factory meshCaCertificateProviderFactory;
  final ScheduledExecutorServiceFactory scheduledExecutorServiceFactory;
  final TimeProvider timeProvider;

  MeshCaCertificateProviderProvider() {
    this(
        StsCredentials.Factory.getInstance(),
        MeshCaCertificateProvider.MeshCaChannelFactory.getInstance(),
        new ExponentialBackoffPolicy.Provider(),
        MeshCaCertificateProvider.Factory.getInstance(),
        ScheduledExecutorServiceFactory.DEFAULT_INSTANCE,
        TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @VisibleForTesting
  MeshCaCertificateProviderProvider(
      StsCredentials.Factory stsCredentialsFactory,
      MeshCaCertificateProvider.MeshCaChannelFactory meshCaChannelFactory,
      BackoffPolicy.Provider backoffPolicyProvider,
      MeshCaCertificateProvider.Factory meshCaCertificateProviderFactory,
      ScheduledExecutorServiceFactory scheduledExecutorServiceFactory,
      TimeProvider timeProvider) {
    this.stsCredentialsFactory = stsCredentialsFactory;
    this.meshCaChannelFactory = meshCaChannelFactory;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.meshCaCertificateProviderFactory = meshCaCertificateProviderFactory;
    this.scheduledExecutorServiceFactory = scheduledExecutorServiceFactory;
    this.timeProvider = timeProvider;
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

    return meshCaCertificateProviderFactory.create(
        watcher,
        notifyCertUpdates,
        configObj.meshCaUrl,
        configObj.zone,
        configObj.certValiditySeconds,
        configObj.keySize,
        configObj.keyAlgo,
        configObj.signatureAlgo,
        meshCaChannelFactory,
        backoffPolicyProvider,
        configObj.renewalGracePeriodSeconds,
        configObj.maxRetryAttempts,
        stsCredentials,
        scheduledExecutorServiceFactory.create(configObj.meshCaUrl),
        timeProvider,
        TimeUnit.SECONDS.toMillis(RPC_TIMEOUT_SECONDS));
  }

  private static Config validateAndTranslateConfig(Object config) {
    // TODO(sanjaypujare): add support for string, struct proto etc
    checkArgument(config instanceof Map, "Only Map supported for config");
    @SuppressWarnings("unchecked") Map<String, ?> map = (Map<String, ?>)config;

    Config configObj = new Config();
    extractMeshCaServerConfig(configObj, getObject(map, SERVER_CONFIG_KEY));
    configObj.certValiditySeconds =
        getSeconds(
            JsonUtil.getObject(map, CERT_VALIDITY_SECONDS_KEY), CERT_VALIDITY_SECONDS_DEFAULT);
    configObj.renewalGracePeriodSeconds =
        getSeconds(
            JsonUtil.getObject(map, RENEWAL_GRACE_PERIOD_SECONDS_KEY),
            RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT);
    String keyType = JsonUtil.getString(map, KEY_ALGO_KEY);
    checkArgument(
        keyType == null || keyType.equals(KEY_ALGO_DEFAULT), "key_type can only be null or 'RSA'");
    // TODO: remove signatureAlgo, keyType (or keyAlgo), maxRetryAttempts
    configObj.maxRetryAttempts = MAX_RETRY_ATTEMPTS_DEFAULT;
    configObj.keyAlgo = KEY_ALGO_DEFAULT;
    configObj.signatureAlgo = SIGNATURE_ALGO_DEFAULT;
    configObj.keySize = JsonUtil.getNumberAsInteger(map, KEY_SIZE_KEY);
    if (configObj.keySize == null) {
      configObj.keySize = KEY_SIZE_DEFAULT;
    }
    configObj.gkeClusterUrl =
            checkNotNull(JsonUtil.getString(map, GKECLUSTER_URL_KEY),
                    "'location' is required in the config");
    parseProjectAndZone(configObj.gkeClusterUrl, configObj);
    return configObj;
  }

  private static void extractMeshCaServerConfig(Config configObj, Map<String, ?> serverConfig) {
    // init with defaults
    configObj.meshCaUrl = MESHCA_URL_DEFAULT;
    configObj.rpcTimeoutSeconds = RPC_TIMEOUT_SECONDS_DEFAULT;
    configObj.stsUrl = STS_URL_DEFAULT;
    if (serverConfig != null) {
      checkArgument(
          "GRPC".equals(JsonUtil.getString(serverConfig, "api_type")),
          "Only GRPC api_type supported");
      List<Map<String, ?>> grpcServices =
          checkNotNull(
              JsonUtil.getListOfObjects(serverConfig, "grpc_services"), "grpc_services not found");
      for (Map<String, ?> grpcService : grpcServices) {
        Map<String, ?> googleGrpcConfig = JsonUtil.getObject(grpcService, "google_grpc");
        if (googleGrpcConfig != null) {
          String value = JsonUtil.getString(googleGrpcConfig, MESHCA_URL_KEY);
          if (value != null) {
            configObj.meshCaUrl = value;
          }
          Map<String, ?> channelCreds =
                  JsonUtil.getObject(googleGrpcConfig, "channel_credentials");
          if (channelCreds != null) {
            Map<String, ?> googleDefaultChannelCreds =
                checkNotNull(
                    JsonUtil.getObject(channelCreds, "google_default"),
                    "channel_credentials need to be google_default!");
            checkArgument(
                googleDefaultChannelCreds.isEmpty(),
                "google_default credentials contain illegal value");
          }
          List<Map<String, ?>> callCreds =
              JsonUtil.getListOfObjects(googleGrpcConfig, "call_credentials");
          for (Map<String, ?> callCred : callCreds) {
            Map<String, ?> stsCreds = JsonUtil.getObject(callCred, STS_SERVICE_KEY);
            if (stsCreds != null) {
              value = JsonUtil.getString(stsCreds, TOKEN_EXCHANGE_SERVICE_KEY);
              if (value != null) {
                configObj.stsUrl = value;
              }
              configObj.gkeSaJwtLocation = JsonUtil.getString(stsCreds, GKE_SA_JWT_LOCATION_KEY);
            }
          }
          configObj.rpcTimeoutSeconds =
              getSeconds(
                  JsonUtil.getObject(grpcService, RPC_TIMEOUT_SECONDS_KEY),
                  RPC_TIMEOUT_SECONDS_DEFAULT);
        }
      }
    }
    // check required value(s)
    checkNotNull(configObj.gkeSaJwtLocation, "'subject_token_path' is required in the config");
  }

  private static Long getSeconds(Map<String,?> duration, long defaultValue) {
    if (duration != null) {
      return JsonUtil.getNumberAsLong(duration, "seconds");
    }
    return defaultValue;
  }

  private static void parseProjectAndZone(String gkeClusterUrl, Config configObj) {
    Matcher matcher = CLUSTER_URL_PATTERN.matcher(gkeClusterUrl);
    checkState(matcher.find(), "gkeClusterUrl does not have correct format");
    checkState(matcher.groupCount() == 2, "gkeClusterUrl does not have project and location parts");
    configObj.project = matcher.group(1);
    configObj.zone = matcher.group(2);
  }

  abstract static class ScheduledExecutorServiceFactory {

    private static final ScheduledExecutorServiceFactory DEFAULT_INSTANCE =
        new ScheduledExecutorServiceFactory() {

          @Override
          ScheduledExecutorService create(String serverUri) {
            return Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                    .setNameFormat("meshca-" + serverUri + "-%d")
                    .setDaemon(true)
                    .build());
          }
        };

    static ScheduledExecutorServiceFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract ScheduledExecutorService create(String serverUri);
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
