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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.GcFinalization;
import com.google.common.testing.GcFinalization.FinalizationPredicate;
import io.grpc.xds.SharedCallCounterMap.CounterReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link SharedCallCounterMap}.
 */
@RunWith(JUnit4.class)
public class SharedCallCounterMapTest {

  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = null;

  private final Map<String, Map<String, CounterReference>> counters = new HashMap<>();
  private final SharedCallCounterMap map = new SharedCallCounterMap(counters);

  @Test
  public void sharedCounterInstance() {
    AtomicLong counter1 = map.getOrCreate(CLUSTER, EDS_SERVICE_NAME);
    AtomicLong counter2 = map.getOrCreate(CLUSTER, EDS_SERVICE_NAME);
    assertThat(counter2).isSameInstanceAs(counter1);
  }

  @Test
  public void autoCleanUp() {
    @SuppressWarnings("UnusedVariable")
    AtomicLong counter = map.getOrCreate(CLUSTER, EDS_SERVICE_NAME);
    final CounterReference ref = counters.get(CLUSTER).get(EDS_SERVICE_NAME);
    counter = null;
    GcFinalization.awaitDone(new FinalizationPredicate() {
      @Override
      public boolean isDone() {
        return ref.isEnqueued();
      }
    });
    map.cleanQueue();
    assertThat(counters).isEmpty();
  }
}
