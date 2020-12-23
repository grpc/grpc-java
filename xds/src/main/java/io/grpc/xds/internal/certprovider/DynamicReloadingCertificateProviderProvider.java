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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.TimeProvider;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Provider of {@link DynamicReloadingCertificateProvider}s.
 */
final class DynamicReloadingCertificateProviderProvider implements CertificateProviderProvider {

  private static final String DIRECTORY_KEY = "directory";
  private static final String CERT_FILE_KEY = "certificate-file";
  private static final String KEY_FILE_KEY = "private-key-file";
  private static final String ROOT_FILE_KEY = "ca-certificate-file";
  private static final String REFRESH_INTERVAL_KEY = "refresh-interval";

  @VisibleForTesting static final long REFRESH_INTERVAL_DEFAULT = 600L;


  static final String DYNAMIC_RELOADING_PROVIDER_NAME = "gke-cas-certs";

  final DynamicReloadingCertificateProvider.Factory dynamicReloadingCertificateProviderFactory;
  private final ScheduledExecutorServiceFactory scheduledExecutorServiceFactory;
  private final TimeProvider timeProvider;

  DynamicReloadingCertificateProviderProvider() {
    this(
        DynamicReloadingCertificateProvider.Factory.getInstance(),
        ScheduledExecutorServiceFactory.DEFAULT_INSTANCE,
        TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @VisibleForTesting
  DynamicReloadingCertificateProviderProvider(
      DynamicReloadingCertificateProvider.Factory dynamicReloadingCertificateProviderFactory,
      ScheduledExecutorServiceFactory scheduledExecutorServiceFactory,
      TimeProvider timeProvider) {
    this.dynamicReloadingCertificateProviderFactory = dynamicReloadingCertificateProviderFactory;
    this.scheduledExecutorServiceFactory = scheduledExecutorServiceFactory;
    this.timeProvider = timeProvider;
  }

  @Override
  public String getName() {
    return DYNAMIC_RELOADING_PROVIDER_NAME;
  }

  @Override
  public CertificateProvider createCertificateProvider(
      Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates) {

    Config configObj = validateAndTranslateConfig(config);
    return dynamicReloadingCertificateProviderFactory.create(
        watcher,
        notifyCertUpdates,
        configObj.directory,
        configObj.certFile,
        configObj.keyFile,
        configObj.rootFile,
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
    configObj.directory = checkForNullAndGet(map, DIRECTORY_KEY);
    configObj.certFile = checkForNullAndGet(map, CERT_FILE_KEY);
    configObj.keyFile = checkForNullAndGet(map, KEY_FILE_KEY);
    configObj.rootFile = checkForNullAndGet(map, ROOT_FILE_KEY);
    configObj.refrehInterval = JsonUtil.getNumberAsLong(map, REFRESH_INTERVAL_KEY);
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
                    .setNameFormat("dynamicReloading" + "-%d")
                    .setDaemon(true)
                    .build());
          }
        };

    abstract ScheduledExecutorService create();
  }

  /** POJO class for storing various config values. */
  @VisibleForTesting
  static class Config {
    String directory;
    String certFile;
    String keyFile;
    String rootFile;
    Long refrehInterval;
  }
}
