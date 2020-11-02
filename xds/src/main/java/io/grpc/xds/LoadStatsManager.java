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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
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
  private final Map<String, Map<String, ReferenceCounted<LoadStatsStore>>> loadStatsStores
      = new HashMap<>();

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
      loadStatsStores.put(cluster, new HashMap<String, ReferenceCounted<LoadStatsStore>>());
    }
    Map<String, ReferenceCounted<LoadStatsStore>> clusterLoadStatsStores
        = loadStatsStores.get(cluster);
    if (!clusterLoadStatsStores.containsKey(clusterService)) {
      clusterLoadStatsStores.put(
          clusterService,
          ReferenceCounted.wrap(loadStatsStoreFactory.newLoadStatsStore(cluster, clusterService)));
    }
    ReferenceCounted<LoadStatsStore> ref = clusterLoadStatsStores.get(clusterService);
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
    Map<String, ReferenceCounted<LoadStatsStore>> clusterLoadStatsStores =
        loadStatsStores.get(cluster);
    ReferenceCounted<LoadStatsStore> ref = clusterLoadStatsStores.get(clusterService);
    ref.release();
    if (ref.getReferenceCount() == 0) {
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
  List<ClusterStats> getClusterLoadReports(String cluster) {
    List<ClusterStats> res = new ArrayList<>();
    Map<String, ReferenceCounted<LoadStatsStore>> clusterLoadStatsStores =
        loadStatsStores.get(cluster);
    if (clusterLoadStatsStores == null) {
      return res;
    }
    for (ReferenceCounted<LoadStatsStore> ref : clusterLoadStatsStores.values()) {
      res.add(ref.get().generateLoadReport());
    }
    return res;
  }

  /**
   * Generates reports summarized the stats recorded for loads sent to all clusters for the
   * interval between calls of this method or {@link #getClusterLoadReports}. Each report
   * includes stats for one cluster:cluster_service.
   */
  List<ClusterStats> getAllLoadReports() {
    List<ClusterStats> res = new ArrayList<>();
    for (Map<String, ReferenceCounted<LoadStatsStore>> clusterLoadStatsStores
        : loadStatsStores.values()) {
      for (ReferenceCounted<LoadStatsStore> ref : clusterLoadStatsStores.values()) {
        res.add(ref.get().generateLoadReport());
      }
    }
    return res;
  }

  // Introduced for testing.
  @VisibleForTesting
  interface LoadStatsStoreFactory {
    LoadStatsStore newLoadStatsStore(String cluster, String clusterService);
  }

  /**
   * Interface for client side load stats store. A {@link LoadStatsStore} instance holds the load
   * stats for a cluster from an gRPC client's perspective by maintaining a set of locality
   * counters for each locality it is tracking loads for.
   */
  interface LoadStatsStore {

    /**
     * Generates a report based on recorded load stats (including RPC counts, backend metrics and
     * dropped calls) for the interval since the previous call of this method.
     */
    ClusterStats generateLoadReport();

    /**
     * Adds tracking for load stats sent to the given {@code locality}. Returns the counter
     * object responsible for tracking the client load stats to the given {@code locality}.
     * Only load stats for tracked localities will be included in generated load reports.
     */
    ClientLoadCounter addLocality(Locality locality);

    /**
     * Drops tracking for load stats sent to the given {@code locality}. Load stats for removed
     * localities will no longer be included in future generated load reports after
     * their currently recording stats have been fully reported.
     */
    void removeLocality(Locality locality);

    /**
     * Records a drop decision with the given category.
     *
     * <p>This method must be thread-safe.
     */
    void recordDroppedRequest(String category);

    /**
     * Records a uncategorized drop decision.
     *
     * <p>This method must be thread-safe.
     */
    void recordDroppedRequest();
  }
}
