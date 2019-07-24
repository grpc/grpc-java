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

package io.grpc.services;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BinlogHelper}. */
@RunWith(JUnit4.class)
public class CallMetricRecorderTest {

  private final CallMetricRecorder recorder = new CallMetricRecorder();

  @Test
  public void dumpGivesEmptyResultWhenNoSavedMetricValues() {
    assertThat(recorder.finalizeAndDump()).isEmpty();
  }

  @Test
  public void dumpDumpsAllSavedMetricValues() {
    recorder.recordCallMetric("ssd", 154353.423);
    recorder.recordCallMetric("cpu", 0.1367);
    recorder.recordCallMetric("mem", 1437.34);

    Map<String, Double> dump = recorder.finalizeAndDump();
    assertThat(dump)
        .containsExactly("ssd", 154353.423, "cpu", 0.1367, "mem", 1437.34);
  }

  @Test
  public void noMetricsRecordedAfterSnapshot() {
    Map<String, Double> initDump = recorder.finalizeAndDump();
    recorder.recordCallMetric("cpu", 154353.423);
    assertThat(recorder.finalizeAndDump()).isEqualTo(initDump);
  }
}
