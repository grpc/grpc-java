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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.TimeProvider;
import java.text.ParseException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Provider of {@link FileWatcherCertificateProvider}s.
 */
public final class FileWatcherCertificateProviderProvider implements CertificateProviderProvider {

  @VisibleForTesting
  public static boolean enableSpiffe = GrpcUtil.getFlag("GRPC_EXPERIMENTAL_SPIFFE_TRUST_BUNDLE_MAP",
      false);
  private static final String CERT_FILE_KEY = "certificate_file";
  private static final String KEY_FILE_KEY = "private_key_file";
  private static final String ROOT_FILE_KEY = "ca_certificate_file";
  private static final String SPIFFE_TRUST_MAP_FILE_KEY = "spiffe_trust_bundle_map_file";
  private static final String REFRESH_INTERVAL_KEY = "refresh_interval";

  @VisibleForTesting static final long REFRESH_INTERVAL_DEFAULT = 600L;


  static final String FILE_WATCHER_PROVIDER_NAME = "file_watcher";

  final FileWatcherCertificateProvider.Factory fileWatcherCertificateProviderFactory;
  private final ScheduledExecutorServiceFactory scheduledExecutorServiceFactory;
  private final TimeProvider timeProvider;

  FileWatcherCertificateProviderProvider() {
    this(
        FileWatcherCertificateProvider.Factory.getInstance(),
        ScheduledExecutorServiceFactory.DEFAULT_INSTANCE,
        TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @VisibleForTesting
  FileWatcherCertificateProviderProvider(
      FileWatcherCertificateProvider.Factory fileWatcherCertificateProviderFactory,
      ScheduledExecutorServiceFactory scheduledExecutorServiceFactory,
      TimeProvider timeProvider) {
    this.fileWatcherCertificateProviderFactory = fileWatcherCertificateProviderFactory;
    this.scheduledExecutorServiceFactory = scheduledExecutorServiceFactory;
    this.timeProvider = timeProvider;
  }

  @Override
  public String getName() {
    return FILE_WATCHER_PROVIDER_NAME;
  }

  @Override
  public CertificateProvider createCertificateProvider(
      Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates) {

    Config configObj = validateAndTranslateConfig(config);
    return fileWatcherCertificateProviderFactory.create(
        watcher,
        notifyCertUpdates,
        configObj.certFile,
        configObj.keyFile,
        configObj.rootFile,
        configObj.spiffeTrustMapFile,
        configObj.refrehInterval,
        scheduledExecutorServiceFactory.create(),
        timeProvider);
  }

  private static String checkForNullAndGet(Map<String, ?> map, String key) {
    return checkNotNull(JsonUtil.getString(map, key), "'" + key + "' is required in the config");
  }

  private static Config validateAndTranslateConfig(Object config) {
    checkArgument(config instanceof Map, "Only Map supported for config");
    @SuppressWarnings("unchecked") Map<String, ?> map = (Map<String, ?>)config;

    Config configObj = new Config();
    configObj.certFile = checkForNullAndGet(map, CERT_FILE_KEY);
    configObj.keyFile = checkForNullAndGet(map, KEY_FILE_KEY);
    if (enableSpiffe) {
      if (!map.containsKey(ROOT_FILE_KEY) && !map.containsKey(SPIFFE_TRUST_MAP_FILE_KEY)) {
        throw new NullPointerException(
            String.format("either '%s' or '%s' is required in the config",
                ROOT_FILE_KEY, SPIFFE_TRUST_MAP_FILE_KEY));
      }
      if (map.containsKey(SPIFFE_TRUST_MAP_FILE_KEY)) {
        configObj.spiffeTrustMapFile = JsonUtil.getString(map, SPIFFE_TRUST_MAP_FILE_KEY);
      } else {
        configObj.rootFile = JsonUtil.getString(map, ROOT_FILE_KEY);
      }
    } else {
      configObj.rootFile = checkForNullAndGet(map, ROOT_FILE_KEY);
    }
    String refreshIntervalString = JsonUtil.getString(map, REFRESH_INTERVAL_KEY);
    if (refreshIntervalString != null) {
      try {
        Duration duration = Durations.parse(refreshIntervalString);
        configObj.refrehInterval = duration.getSeconds();
        checkArgument(configObj.refrehInterval > 0L, "refreshInterval needs to be greater than 0");
      } catch (ParseException e) {
        throw new IllegalArgumentException(e);
      }
    }
    if (configObj.refrehInterval == null) {
      configObj.refrehInterval = REFRESH_INTERVAL_DEFAULT;
    }
    return configObj;
  }

  abstract static class ScheduledExecutorServiceFactory {

    private static final ScheduledExecutorServiceFactory DEFAULT_INSTANCE =
        new ScheduledExecutorServiceFactory() {

          @Override
          ScheduledExecutorService create() {
            return Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                    .setNameFormat("fileWatcher" + "-%d")
                    .setDaemon(true)
                    .build());
          }
        };

    abstract ScheduledExecutorService create();
  }

  /** POJO class for storing various config values. */
  @VisibleForTesting
  static class Config {
    String certFile;
    String keyFile;
    String rootFile;
    String spiffeTrustMapFile;
    Long refrehInterval;
  }
}
