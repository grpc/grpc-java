/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.XdsNameResolver.CLUSTER_SELECTION_KEY;
import static io.grpc.xds.XdsNameResolver.XDS_CONFIG_CALL_OPTION_KEY;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.UnsignedLongs;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.Audience;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.TokenCacheConfig;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.CompositeCallCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.xds.GcpAuthenticationFilter.AudienceMetadataParser.AudienceWrapper;
import io.grpc.xds.MetadataRegistry.MetadataValueParser;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A {@link Filter} that injects a {@link CallCredentials} to handle
 * authentication for xDS credentials.
 */
final class GcpAuthenticationFilter implements Filter {

  static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig";
  private final LruCache<String, CallCredentials> callCredentialsCache;
  final String filterInstanceName;

  GcpAuthenticationFilter(String name, int cacheSize) {
    filterInstanceName = checkNotNull(name, "name");
    this.callCredentialsCache = new LruCache<>(cacheSize);
  }

  static final class Provider implements Filter.Provider {
    private final int cacheSize = 10;

    @Override
    public String[] typeUrls() {
      return new String[]{TYPE_URL};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public GcpAuthenticationFilter newInstance(String name) {
      return new GcpAuthenticationFilter(name, cacheSize);
    }

    @Override
    public ConfigOrError<GcpAuthenticationConfig> parseFilterConfig(Message rawProtoMessage) {
      GcpAuthnFilterConfig gcpAuthnProto;
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      Any anyMessage = (Any) rawProtoMessage;

      try {
        gcpAuthnProto = anyMessage.unpack(GcpAuthnFilterConfig.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }

      long cacheSize = 10;
      // Validate cache_config
      if (gcpAuthnProto.hasCacheConfig()) {
        TokenCacheConfig cacheConfig = gcpAuthnProto.getCacheConfig();
        if (cacheConfig.hasCacheSize()) {
          cacheSize = cacheConfig.getCacheSize().getValue();
          if (cacheSize == 0) {
            return ConfigOrError.fromError(
                "cache_config.cache_size must be greater than zero");
          }
        }

        // LruCache's size is an int and briefly exceeds its maximum size before evicting entries
        cacheSize = UnsignedLongs.min(cacheSize, Integer.MAX_VALUE - 1);
      }

      GcpAuthenticationConfig config = new GcpAuthenticationConfig((int) cacheSize);
      return ConfigOrError.fromConfig(config);
    }

    @Override
    public ConfigOrError<GcpAuthenticationConfig> parseFilterConfigOverride(
        Message rawProtoMessage) {
      return parseFilterConfig(rawProtoMessage);
    }
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig config,
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {

    ComputeEngineCredentials credentials = ComputeEngineCredentials.create();
    synchronized (callCredentialsCache) {
      callCredentialsCache.resizeCache(((GcpAuthenticationConfig) config).getCacheSize());
    }
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        String clusterName = callOptions.getOption(CLUSTER_SELECTION_KEY);
        if (clusterName == null) {
          return new FailingClientCall<>(
              Status.UNAVAILABLE.withDescription(
                  String.format(
                      "GCP Authn for %s does not contain cluster resource", filterInstanceName)));
        }

        if (!clusterName.startsWith("cluster:")) {
          return next.newCall(method, callOptions);
        }
        XdsConfig xdsConfig = callOptions.getOption(XDS_CONFIG_CALL_OPTION_KEY);
        if (xdsConfig == null) {
          return new FailingClientCall<>(
              Status.UNAVAILABLE.withDescription(
                  String.format(
                      "GCP Authn for %s with %s does not contain xds configuration",
                      filterInstanceName, clusterName)));
        }
        StatusOr<XdsClusterConfig> xdsCluster =
            xdsConfig.getClusters().get(clusterName.substring("cluster:".length()));
        if (xdsCluster == null) {
          return new FailingClientCall<>(
              Status.UNAVAILABLE.withDescription(
                  String.format(
                      "GCP Authn for %s with %s - xds cluster config does not contain xds cluster",
                      filterInstanceName, clusterName)));
        }
        if (!xdsCluster.hasValue()) {
          return new FailingClientCall<>(xdsCluster.getStatus());
        }
        Object audienceObj =
            xdsCluster.getValue().getClusterResource().parsedMetadata().get(filterInstanceName);
        if (audienceObj == null) {
          return next.newCall(method, callOptions);
        }
        if (!(audienceObj instanceof AudienceWrapper)) {
          return new FailingClientCall<>(
              Status.UNAVAILABLE.withDescription(
                  String.format("GCP Authn found wrong type in %s metadata: %s=%s",
                      clusterName, filterInstanceName, audienceObj.getClass())));
        }
        AudienceWrapper audience = (AudienceWrapper) audienceObj;
        CallCredentials existingCallCredentials = callOptions.getCredentials();
        CallCredentials newCallCredentials =
            getCallCredentials(callCredentialsCache, audience.audience, credentials);
        if (existingCallCredentials != null) {
          callOptions = callOptions.withCallCredentials(
              new CompositeCallCredentials(existingCallCredentials, newCallCredentials));
        } else {
          callOptions = callOptions.withCallCredentials(newCallCredentials);
        }
        return next.newCall(method, callOptions);
      }
    };
  }

  private CallCredentials getCallCredentials(LruCache<String, CallCredentials> cache,
      String audience, ComputeEngineCredentials credentials) {

    synchronized (cache) {
      return cache.getOrInsert(audience, key -> {
        IdTokenCredentials creds = IdTokenCredentials.newBuilder()
            .setIdTokenProvider(credentials)
            .setTargetAudience(audience)
            .build();
        return MoreCallCredentials.from(creds);
      });
    }
  }

  static final class GcpAuthenticationConfig implements FilterConfig {

    private final int cacheSize;

    public GcpAuthenticationConfig(int cacheSize) {
      this.cacheSize = cacheSize;
    }

    public int getCacheSize() {
      return cacheSize;
    }

    @Override
    public String typeUrl() {
      return GcpAuthenticationFilter.TYPE_URL;
    }
  }

  /** An implementation of {@link ClientCall} that fails when started. */
  @VisibleForTesting
  static final class FailingClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

    @VisibleForTesting
    final Status error;

    public FailingClientCall(Status error) {
      this.error = error;
    }

    @Override
    public void start(ClientCall.Listener<RespT> listener, Metadata headers) {
      listener.onClose(error, new Metadata());
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(String message, Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(ReqT message) {}
  }

  private static final class LruCache<K, V> {

    private Map<K, V> cache;
    private int maxSize;

    LruCache(int maxSize) {
      this.maxSize = maxSize;
      this.cache = createEvictingMap(maxSize);
    }

    V getOrInsert(K key, Function<K, V> create) {
      return cache.computeIfAbsent(key, create);
    }

    private void resizeCache(int newSize) {
      if (newSize >= maxSize) {
        maxSize = newSize;
        return;
      }
      Map<K, V> newCache = createEvictingMap(newSize);
      maxSize = newSize;
      newCache.putAll(cache);
      cache = newCache;
    }

    private Map<K, V> createEvictingMap(int size) {
      return new LinkedHashMap<K, V>(size, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
          return size() > LruCache.this.maxSize;
        }
      };
    }
  }

  static class AudienceMetadataParser implements MetadataValueParser {

    static final class AudienceWrapper {
      final String audience;

      AudienceWrapper(String audience) {
        this.audience = checkNotNull(audience);
      }
    }

    @Override
    public String getTypeUrl() {
      return "type.googleapis.com/envoy.extensions.filters.http.gcp_authn.v3.Audience";
    }

    @Override
    public AudienceWrapper parse(Any any) throws ResourceInvalidException {
      Audience audience;
      try {
        audience = any.unpack(Audience.class);
      } catch (InvalidProtocolBufferException ex) {
        throw new ResourceInvalidException("Invalid Resource in address proto", ex);
      }
      String url = audience.getUrl();
      if (url.isEmpty()) {
        throw new ResourceInvalidException(
            "Audience URL is empty. Metadata value must contain a valid URL.");
      }
      return new AudienceWrapper(url);
    }
  }
}
