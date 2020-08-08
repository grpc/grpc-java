/*
 * Copyright 2019 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Manages all stats for client side load.
 */
final class LoadStatsManager {
  private final LoadStatsStoreFactory loadStatsStoreFactory;
  private final Map<String, Map<String, RefCounted>> loadStatsStores = new HashMap<>();

  LoadStatsManager() {
    this(LoadStatsStoreImpl.getDefaultFactory());
  }

  @VisibleForTesting
  LoadStatsManager(LoadStatsStoreFactory factory) {
    this.loadStatsStoreFactory = factory;
  }

  /**
   * Adds and retrieves the stats object for tracking loads for the given cluster:cluster_service.
   * The returned {@link LoadStatsStore} is reference-counted, caller should use
   * {@link #removeLoadStats} to release the reference when it is no longer used.
   */
  LoadStatsStore addLoadStats(String cluster, @Nullable String clusterService) {
    if (!loadStatsStores.containsKey(cluster)) {
      loadStatsStores.put(cluster, new HashMap<String, RefCounted>());
    }
    Map<String, RefCounted> clusterLoadStatsStores = loadStatsStores.get(cluster);
    if (!clusterLoadStatsStores.containsKey(clusterService)) {
      clusterLoadStatsStores.put(
          clusterService,
          new RefCounted(loadStatsStoreFactory.newLoadStatsStore(cluster, clusterService)));
    }
    RefCounted ref = clusterLoadStatsStores.get(clusterService);
    ref.retain();
    return ref.get();
  }

  /**
   * Discards stats object used for tracking loads for the given cluster:cluster_service.
   */
  void removeLoadStats(String cluster, @Nullable String clusterService) {
    checkState(
        loadStatsStores.containsKey(cluster)
            && loadStatsStores.get(cluster).containsKey(clusterService),
        "stats for cluster %s, cluster service %s not exits");
    Map<String, RefCounted> clusterLoadStatsStores = loadStatsStores.get(cluster);
    RefCounted ref = clusterLoadStatsStores.get(clusterService);
    ref.release();
    if (ref.getRefCount() == 0) {
      clusterLoadStatsStores.remove(clusterService);
    }
    if (clusterLoadStatsStores.isEmpty()) {
      loadStatsStores.remove(cluster);
    }
  }

  /**
   * Generates reports summarizing the stats recorded for loads sent to the given cluster for
   * the interval between calls of this method or {@link #getAllLoadReports}. A cluster may send
   * loads to more than one cluster_service, they are included in separate stats reports.
   */
  // TODO(chengyuanzhang): do not use proto type directly.
  List<ClusterStats> getClusterLoadReports(String cluster) {
    List<ClusterStats> res = new ArrayList<>();
    Map<String, RefCounted> clusterLoadStatsStores = loadStatsStores.get(cluster);
    if (clusterLoadStatsStores == null) {
      return res;
    }
    for (RefCounted ref : clusterLoadStatsStores.values()) {
      res.add(ref.get().generateLoadReport());
    }
    return res;
  }

  /**
   * Generates reports summarized the stats recorded for loads sent to all clusters for the
   * interval between calls of this method or {@link #getClusterLoadReports}. Each report
   * includes stats for one cluster:cluster_service.
   */
  // TODO(chengyuanzhang): do not use proto type directly.
  List<ClusterStats> getAllLoadReports() {
    List<ClusterStats> res = new ArrayList<>();
    for (Map<String, RefCounted> clusterLoadStatsStores : loadStatsStores.values()) {
      for (RefCounted ref : clusterLoadStatsStores.values()) {
        res.add(ref.get().generateLoadReport());
      }
    }
    return res;
  }

  /**
   * A reference-counted wrapper for {@link LoadStatsStore}.
   */
  private static final class RefCounted {
    private final LoadStatsStore instance;
    private int refs = 0;

    RefCounted(LoadStatsStore instance) {
      this.instance = checkNotNull(instance, "instance");
    }

    LoadStatsStore get() {
      checkState(refs > 0, "reference reached 0");
      return instance;
    }

    int getRefCount() {
      return refs;
    }

    void retain() {
      refs++;
    }

    void release() {
      refs--;
    }
  }

  @VisibleForTesting
  interface LoadStatsStoreFactory {
    LoadStatsStore newLoadStatsStore(String cluster, String clusterService);
  }

  /**
   * Interface for client side load stats store. An {@code LoadStatsStore} maintains load stats per
   * cluster:cluster_service exposed by traffic director from a gRPC client's perspective,
   * including dropped calls. Load stats for endpoints are aggregated in locality granularity
   * while the numbers of dropped calls are aggregated in cluster:cluster_service granularity.
   */
  interface LoadStatsStore {

    /**
     * Generates a report based on recorded load stats (including RPC counts, backend metrics and
     * dropped calls) for the interval since the previous call of this method.
     */
    // TODO(chengyuanzhang): do not use proto type directly.
    ClusterStats generateLoadReport();

    /**
     * Starts tracking load stats for endpoints in the provided locality. Only load stats for
     * endpoints in added localities will be recorded and included in generated load reports.
     */
    void addLocality(Locality locality);

    /**
     * Stops tracking load stats for endpoints in the provided locality. Load stats for endpoints
     * in removed localities will no longer be included in future generated load reports after
     * their recorded and ongoing loads have been reported.
     */
    void removeLocality(Locality locality);

    /**
     * Returns the locality counter that does locality level stats aggregation for the provided
     * locality. If the provided locality is not tracked, {@code null} will be returned.
     */
    @Nullable
    ClientLoadCounter getLocalityCounter(Locality locality);

    /**
     * Records a drop decision.
     *
     * <p>This method is thread-safe.
     */
    void recordDroppedRequest(String category);
  }
}
