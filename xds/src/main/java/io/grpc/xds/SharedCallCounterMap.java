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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The global map for holding circuit breaker atomic counters.
 */
@ThreadSafe
final class SharedCallCounterMap implements CallCounterProvider {

  private final ReferenceQueue<AtomicLong> refQueue = new ReferenceQueue<>();
  private final Map<String, Map<String, CounterReference>> counters;

  private SharedCallCounterMap() {
    this(new HashMap<String, Map<String, CounterReference>>());
  }

  @VisibleForTesting
  SharedCallCounterMap(Map<String, Map<String, CounterReference>> counters) {
    this.counters = checkNotNull(counters, "counters");
  }

  static SharedCallCounterMap getInstance() {
    return SharedCallCounterMapHolder.instance;
  }

  @Override
  public synchronized AtomicLong getOrCreate(String cluster, @Nullable String edsServiceName) {
    Map<String, CounterReference> clusterCounters = counters.get(cluster);
    if (clusterCounters == null) {
      clusterCounters = new HashMap<>();
      counters.put(cluster, clusterCounters);
    }
    CounterReference ref = clusterCounters.get(edsServiceName);
    AtomicLong counter;
    if (ref == null || (counter = ref.get()) == null) {
      counter = new AtomicLong();
      ref = new CounterReference(counter, refQueue, cluster, edsServiceName);
      clusterCounters.put(edsServiceName, ref);
    }
    cleanQueue();
    return counter;
  }

  @VisibleForTesting
  void cleanQueue() {
    CounterReference ref;
    while ((ref = (CounterReference) refQueue.poll()) != null) {
      Map<String, CounterReference> clusterCounter = counters.get(ref.cluster);
      clusterCounter.remove(ref.edsServiceName);
      if (clusterCounter.isEmpty()) {
        counters.remove(ref.cluster);
      }
    }
  }

  @VisibleForTesting
  static final class CounterReference extends WeakReference<AtomicLong> {
    private final String cluster;
    @Nullable
    private final String edsServiceName;

    CounterReference(AtomicLong counter, ReferenceQueue<AtomicLong> refQueue, String cluster,
        @Nullable String edsServiceName) {
      super(counter, refQueue);
      this.cluster = cluster;
      this.edsServiceName = edsServiceName;
    }
  }

  private static final class SharedCallCounterMapHolder {
    private static final SharedCallCounterMap instance = new SharedCallCounterMap();
  }
}
