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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.Internal;
import io.grpc.gcp.observability.ObservabilityConfig;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;

/**
 * Parses gRPC GcpObservability configuration filters for interceptors usage.
 */
@Internal
public class ConfigFilterHelper {
  public static final FilterParams NO_FILTER_PARAMS
      = FilterParams.create(false, 0, 0);

  private static final int MAX_CACHED_METHODS = 1024;

  private final FifoCache<String, FilterParams> clientFilterParamsCache;
  private final FifoCache<String, FilterParams> serverFilterParamsCache;

  private ConfigFilterHelper(ObservabilityConfig config) {
    clientFilterParamsCache = new FifoCache<>(MAX_CACHED_METHODS,
        method -> matchFilters(method, config.getClientLogFilters()));
    serverFilterParamsCache = new FifoCache<>(MAX_CACHED_METHODS,
        method -> matchFilters(method, config.getServerLogFilters()));
  }

  /**
   * Creates and returns helper instance for log filtering.
   *
   * @param config processed ObservabilityConfig object
   * @return helper instance for filtering
   */
  public static ConfigFilterHelper getInstance(ObservabilityConfig config) {
    return new ConfigFilterHelper(config);
  }


  /**
   * Checks if the corresponding service/method passed needs to be logged according to user provided
   * observability configuration.
   * Filters are evaluated in text order, first match is used.
   *
   * @param fullMethodName the fully qualified name of the method
   * @param client set to true if method being checked is a client method; false otherwise
   * @return FilterParams object 1. specifies if the corresponding method needs to be logged
   *     (log field will be set to true) 2. values of payload limits retrieved from configuration
   */
  public FilterParams logRpcMethod(String fullMethodName, boolean client) {
    checkNotNull(fullMethodName, "fullMethodName");
    FifoCache<String, FilterParams> cache =
        client ? clientFilterParamsCache : serverFilterParamsCache;
    return cache.get(fullMethodName);
  }

  private static FilterParams matchFilters(String fullMethodName, List<LogFilter> logFilters) {
    String serviceName = fullMethodName.substring(0, fullMethodName.lastIndexOf('/'));
    for (LogFilter logFilter : logFilters) {
      if (logFilter.matchAll
          || logFilter.services.contains(serviceName)
          || logFilter.methods.contains(fullMethodName)) {
        if (logFilter.excludePattern) {
          return NO_FILTER_PARAMS;
        }
        int currentHeaderBytes = logFilter.headerBytes;
        int currentMessageBytes = logFilter.messageBytes;
        return FilterParams.create(true, currentHeaderBytes, currentMessageBytes);
      }
    }
    return NO_FILTER_PARAMS;
  }

  private static final class FifoCache<K, V> {
    private final int maxSize;
    private final Function<? super K, ? extends V> loader;
    private final ConcurrentMap<K, V> entries = new ConcurrentHashMap<>();
    @GuardedBy("this")
    private final ArrayDeque<K> insertionOrder = new ArrayDeque<>();

    FifoCache(int maxSize, Function<? super K, ? extends V> loader) {
      checkArgument(maxSize > 0, "maxSize must be positive");
      this.maxSize = maxSize;
      this.loader = checkNotNull(loader, "loader");
    }

    V get(K key) {
      V value = entries.get(key);
      return value != null ? value : load(key);
    }

    // Keep eviction and insertion together while allowing cache hits to proceed concurrently.
    private synchronized V load(K key) {
      V value = entries.get(key);
      if (value != null) {
        return value;
      }
      value = checkNotNull(loader.apply(key), "loader returned null");
      if (insertionOrder.size() == maxSize) {
        entries.remove(insertionOrder.removeFirst());
      }
      entries.put(key, value);
      insertionOrder.addLast(key);
      return value;
    }
  }

  /**
   * Class containing results for method/service filter information, such as flag for logging
   * method/service and payload limits to be used for filtering.
   */
  @AutoValue
  public abstract static class FilterParams {

    abstract boolean log();

    abstract int headerBytes();

    abstract int messageBytes();

    @VisibleForTesting
    public static FilterParams create(boolean log, int headerBytes, int messageBytes) {
      return new AutoValue_ConfigFilterHelper_FilterParams(
          log, headerBytes, messageBytes);
    }
  }
}
