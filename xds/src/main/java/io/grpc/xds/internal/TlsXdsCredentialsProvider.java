/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds.internal;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.ChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonUtil;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import io.grpc.xds.ResourceAllocatingChannelCredentials;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.Closeable;
import java.io.File;
import java.text.ParseException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A wrapper class that supports {@link TlsChannelCredentials} for Xds
 * by implementing {@link XdsCredentialsProvider}.
 */
public final class TlsXdsCredentialsProvider extends XdsCredentialsProvider {
  private static final Logger logger = Logger.getLogger(TlsXdsCredentialsProvider.class.getName());
  private static final String CREDS_NAME = "tls";
  private static final String CERT_FILE_KEY = "certificate_file";
  private static final String KEY_FILE_KEY = "private_key_file";
  private static final String ROOT_FILE_KEY = "ca_certificate_file";
  private static final String REFRESH_INTERVAL_KEY = "refresh_interval";
  private static final long REFRESH_INTERVAL_DEFAULT = 600L;
  private static final ScheduledExecutorServiceFactory scheduledExecutorServiceFactory =
      ScheduledExecutorServiceFactory.DEFAULT_INSTANCE;

  @Override
  protected ChannelCredentials newChannelCredentials(Map<String, ?> jsonConfig) {
    TlsChannelCredentials.Builder tlsChannelCredsBuilder = TlsChannelCredentials.newBuilder();

    if (jsonConfig == null) {
      return tlsChannelCredsBuilder.build();
    }

    // use refresh interval from bootstrap config if provided; else defaults to 600s
    long refreshIntervalSeconds = REFRESH_INTERVAL_DEFAULT;
    String refreshIntervalFromConfig = JsonUtil.getString(jsonConfig, REFRESH_INTERVAL_KEY);
    if (refreshIntervalFromConfig != null) {
      try {
        Duration duration = Durations.parse(refreshIntervalFromConfig);
        refreshIntervalSeconds = Durations.toSeconds(duration);
      } catch (ParseException e) {
        logger.log(Level.WARNING, "Unable to parse refresh interval", e);
        return null;
      }
    }

    // use trust certificate file path from bootstrap config if provided; else use system default
    String rootCertPath = JsonUtil.getString(jsonConfig, ROOT_FILE_KEY);
    AdvancedTlsX509TrustManager trustManager = null;
    if (rootCertPath != null) {
      trustManager = AdvancedTlsX509TrustManager.newBuilder().build();
      tlsChannelCredsBuilder.trustManager(trustManager);
    }

    // use certificate chain and private key file paths from bootstrap config if provided. Mind that
    // both JSON values must be either set (mTLS case) or both unset (TLS case)
    String certChainPath = JsonUtil.getString(jsonConfig, CERT_FILE_KEY);
    String privateKeyPath = JsonUtil.getString(jsonConfig, KEY_FILE_KEY);
    AdvancedTlsX509KeyManager keyManager = null;
    if (certChainPath != null && privateKeyPath != null) {
      keyManager = new AdvancedTlsX509KeyManager();
      tlsChannelCredsBuilder.keyManager(keyManager);
    } else if (certChainPath != null || privateKeyPath != null) {
      logger.log(Level.WARNING, "Certificate chain and private key must be both set or unset");
      return null;
    }

    return ResourceAllocatingChannelCredentials.create(
        tlsChannelCredsBuilder.build(),
        new ResourcesSupplier(
            refreshIntervalSeconds,
            rootCertPath,
            trustManager,
            certChainPath,
            privateKeyPath,
            keyManager));
  }

  @Override
  protected String getName() {
    return CREDS_NAME;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 5;
  }

  private static final class ResourcesSupplier implements Supplier<ImmutableList<Closeable>> {
    private final long refreshIntervalSeconds;
    private final String rootCertPath;
    private final AdvancedTlsX509TrustManager trustManager;
    private final String certChainPath;
    private final String privateKeyPath;
    private final AdvancedTlsX509KeyManager keyManager;

    ResourcesSupplier(
        long refreshIntervalSeconds,
        String rootCertPath,
        AdvancedTlsX509TrustManager trustManager,
        String certChainPath,
        String privateKeyPath,
        AdvancedTlsX509KeyManager keyManager) {
      this.refreshIntervalSeconds = refreshIntervalSeconds;
      this.rootCertPath = rootCertPath;
      this.trustManager = trustManager;
      this.certChainPath = certChainPath;
      this.privateKeyPath = privateKeyPath;
      this.keyManager = keyManager;
    }

    @Override
    public ImmutableList<Closeable> get() {
      ImmutableList.Builder<Closeable> resourcesBuilder = ImmutableList.builder();

      ScheduledExecutorService scheduledExecutorService =
          (trustManager != null || keyManager != null)
              ? scheduledExecutorService = scheduledExecutorServiceFactory.create()
              : null;
      if (scheduledExecutorService != null) {
        resourcesBuilder.add(asCloseable(scheduledExecutorService));
      }

      if (trustManager != null) {
        try {
          Closeable trustManagerFuture = trustManager.updateTrustCredentials(
              new File(rootCertPath),
              refreshIntervalSeconds,
              TimeUnit.SECONDS,
              scheduledExecutorService);
          resourcesBuilder.add(trustManagerFuture);
        } catch (Exception e) {
          cleanupResources(resourcesBuilder.build());
          throw new RuntimeException("Unable to read root certificates", e);
        }
      }

      if (keyManager != null) {
        try {
          Closeable keyManagerFuture = keyManager.updateIdentityCredentials(
              new File(certChainPath),
              new File(privateKeyPath),
              refreshIntervalSeconds,
              TimeUnit.SECONDS,
              scheduledExecutorService);
          resourcesBuilder.add(keyManagerFuture);
        } catch (Exception e) {
          cleanupResources(resourcesBuilder.build());
          throw new RuntimeException("Unable to read certificate chain or private key", e);
        }
      }

      return resourcesBuilder.build();
    }

    private static Closeable asCloseable(ScheduledExecutorService scheduledExecutorService) {
      return () -> scheduledExecutorService.shutdownNow();
    }

    private static void cleanupResources(ImmutableList<Closeable> resources) {
      for (Closeable resource : resources) {
        GrpcUtil.closeQuietly(resource);
      }
    }
  }

  abstract static class ScheduledExecutorServiceFactory {

    private static final ScheduledExecutorServiceFactory DEFAULT_INSTANCE =
        new ScheduledExecutorServiceFactory() {

          @Override
          ScheduledExecutorService create() {
            return Executors.newSingleThreadScheduledExecutor(
              GrpcUtil.getThreadFactory("grpc-certificate-files-%d", true));
          }
        };

    abstract ScheduledExecutorService create();
  }
}
