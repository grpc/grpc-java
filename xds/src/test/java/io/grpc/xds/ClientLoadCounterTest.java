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

import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientLoadCounter}. */
@RunWith(JUnit4.class)
public class ClientLoadCounterTest {
  private long numInProgressCalls;
  private long numFinishedCalls;
  private long numFailedCalls;
  private ClientLoadCounter counter;

  @Before
  public void setUp() {
    numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    numFinishedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    numFailedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    counter = new ClientLoadCounter(numInProgressCalls, numFinishedCalls, numFailedCalls);
  }

  @Test
  public void snapshotContainsEverything() {
    counter.recordMetric("test-metric-1", 0.75);
    counter.recordMetric("test-metric-2", 0.342);
    counter.recordMetric("test-metric-3", 0.512);
    counter.recordMetric("test-metric-1", 0.543);
    counter.recordMetric("test-metric-1", 4.412);
    counter.recordMetric("test-metric-1", 100.353);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(numFinishedCalls - numFailedCalls);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(numFailedCalls);
    assertThat(snapshot.metricValues.get("test-metric-1").numReports).isEqualTo(4);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-1").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.75 + 0.543 + 4.412 + 100.353));
    assertThat(snapshot.metricValues.get("test-metric-2").numReports).isEqualTo(1);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-2").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.342));
    assertThat(snapshot.metricValues.get("test-metric-3").numReports).isEqualTo(1);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-3").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.512));

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(0);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(0);
    assertThat(snapshot.metricValues).isEmpty();
  }
}
