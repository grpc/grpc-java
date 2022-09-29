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

import com.google.common.truth.Truth;
import io.grpc.Context;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CallMetricRecorder}. */
@RunWith(JUnit4.class)
public class CallMetricRecorderTest {

  private final CallMetricRecorder recorder = new CallMetricRecorder();

  @Test
  public void dumpGivesEmptyResultWhenNoSavedMetricValues() {
    assertThat(recorder.finalizeAndDump()).isEmpty();
  }

  @Test
  public void dumpDumpsAllSavedMetricValues() {
    recorder.recordUtilizationMetric("util1", 154353.423);
    recorder.recordUtilizationMetric("util2", 0.1367);
    recorder.recordUtilizationMetric("util3", 1437.34);
    recorder.recordRequestCostMetric("cost1", 37465.12);
    recorder.recordRequestCostMetric("cost2", 10293.0);
    recorder.recordRequestCostMetric("cost3", 1.0);
    recorder.recordCpuUtilizationMetric(0.1928);
    recorder.recordMemoryUtilizationMetric(47.4);

    MetricReport dump = recorder.finalizeAndDump2();
    Truth.assertThat(dump.getUtilizationMetrics())
        .containsExactly("util1", 154353.423, "util2", 0.1367, "util3", 1437.34);
    Truth.assertThat(dump.getRequestCostMetrics())
        .containsExactly("cost1", 37465.12, "cost2", 10293.0, "cost3", 1.0);
    Truth.assertThat(dump.getCpuUtilization()).isEqualTo(0.1928);
    Truth.assertThat(dump.getMemoryUtilization()).isEqualTo(47.4);
  }

  @Test
  public void noMetricsRecordedAfterSnapshot() {
    Map<String, Double> initDump = recorder.finalizeAndDump();
    recorder.recordUtilizationMetric("cost", 154353.423);
    assertThat(recorder.finalizeAndDump()).isEqualTo(initDump);
  }

  @Test
  public void lastValueWinForMetricsWithSameName() {
    recorder.recordRequestCostMetric("cost1", 3412.5435);
    recorder.recordRequestCostMetric("cost2", 6441.341);
    recorder.recordRequestCostMetric("cost1", 6441.341);
    recorder.recordRequestCostMetric("cost1", 4654.67);
    recorder.recordRequestCostMetric("cost2", 75.83);
    recorder.recordMemoryUtilizationMetric(1.3);
    recorder.recordMemoryUtilizationMetric(3.1);
    recorder.recordUtilizationMetric("util1", 28374.21);
    recorder.recordMemoryUtilizationMetric(9384.0);
    recorder.recordUtilizationMetric("util1", 84323.3);

    MetricReport dump = recorder.finalizeAndDump2();
    Truth.assertThat(dump.getRequestCostMetrics())
        .containsExactly("cost1", 4654.67, "cost2", 75.83);
    Truth.assertThat(dump.getMemoryUtilization()).isEqualTo(9384.0);
    Truth.assertThat(dump.getUtilizationMetrics())
        .containsExactly("util1", 84323.3);
    Truth.assertThat(dump.getCpuUtilization()).isEqualTo(0);
  }

  @Test
  public void getCurrent_sameEnabledInstance() {
    CallMetricRecorder recorder = new CallMetricRecorder();
    Context ctx = Context.ROOT.withValue(CallMetricRecorder.CONTEXT_KEY, recorder);
    Context origCtx = ctx.attach();
    try {
      assertThat(CallMetricRecorder.getCurrent()).isSameInstanceAs(recorder);
      assertThat(recorder.isDisabled()).isFalse();
    } finally {
      ctx.detach(origCtx);
    }
  }

  @Test
  public void getCurrent_blankContext() {
    Context blankCtx = Context.ROOT;
    Context origCtx = blankCtx.attach();
    try {
      assertThat(CallMetricRecorder.getCurrent().isDisabled()).isTrue();
    } finally {
      blankCtx.detach(origCtx);
    }
  }
}
