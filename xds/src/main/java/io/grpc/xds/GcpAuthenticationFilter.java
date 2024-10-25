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

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.TokenCacheConfig;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.CompositeCallCredentials;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A {@link Filter} that injects a {@link CallCredentials} to handle
 * authentication for xDS credentials.
 */
final class GcpAuthenticationFilter implements Filter, ClientInterceptorBuilder {

  static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig";

  public GcpAuthenticationFilter() {

  }

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL };
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfig(Message rawProtoMessage) {
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
    TokenCacheConfig cacheConfig = gcpAuthnProto.getCacheConfig();
    if (cacheConfig != null) {
      cacheSize = cacheConfig.getCacheSize().getValue();
      if (cacheSize <= 0) {
        return ConfigOrError.fromError(
            "cache_config.cache_size must be in the range (0, INT64_MAX)");
      }
    }

    // Create and return the GcpAuthenticationConfig
    GcpAuthenticationConfig config = new GcpAuthenticationConfig(cacheSize);
    return ConfigOrError.fromConfig(config);
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    return parseFilterConfig(rawProtoMessage);
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig config,
      @Nullable FilterConfig overrideConfig, PickSubchannelArgs args,
      ScheduledExecutorService scheduler) {

    ComputeEngineCredentials credentials = ComputeEngineCredentials.create();
    LruCache<String, CallCredentials> callCredentialsCache =
        new LruCache<>(((GcpAuthenticationConfig) config).getCacheSize());
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        /*String clusterName = callOptions.getOption(InternalXdsAttributes.ATTR_CLUSTER_NAME);
        if (clusterName == null) {
          return next.newCall(method, callOptions);
        }*/

        // TODO: Fetch the CDS resource for the cluster.
        // If the CDS resource is not available, fail the RPC with Status.UNAVAILABLE.

        // TODO: Extract the audience from the CDS resource metadata.
        // If the audience is not found or is in the wrong format, fail the RPC.
        String audience = "TEST_AUDIENCE";

        try {
          CallCredentials existingCallCredentials = callOptions.getCredentials();
          CallCredentials newCallCredentials =
              getCallCredentials(callCredentialsCache, audience, credentials);
          if (existingCallCredentials != null) {
            callOptions = callOptions.withCallCredentials(
                new CompositeCallCredentials(existingCallCredentials, newCallCredentials));
          } else {
            callOptions = callOptions.withCallCredentials(newCallCredentials);
          }
        }
        catch (Exception e) {
          // If we fail to attach CallCredentials due to any reason, return a FailingClientCall
          return new FailingClientCall<>(Status.UNAUTHENTICATED
              .withDescription("Failed to attach CallCredentials.")
              .withCause(e));
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

  private static final class GcpAuthenticationConfig implements FilterConfig {

    private final long cacheSize;

    public GcpAuthenticationConfig(long cacheSize) {
      this.cacheSize = cacheSize;
    }

    public Long getCacheSize() {
      return cacheSize;
    }

    @Override
    public String typeUrl() {
      return GcpAuthenticationFilter.TYPE_URL;
    }
  }

  /** An implementation of {@link ClientCall} that fails when started. */
  private static final class FailingClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

    private final Status error;

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

    private final Map<K, V> cache;

    LruCache(long maxSize) {
      this.cache = new LinkedHashMap<K, V>(
          (int) Math.min(maxSize, Integer.MAX_VALUE - 1),
          0.75f,
          true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
          return size() > maxSize;
        }
      };
    }

    V get(K key) {
      return cache.get(key);
    }

    V getOrInsert(K key, Function<K, V> create) {
      return cache.computeIfAbsent(key, create);
    }
  }
}
