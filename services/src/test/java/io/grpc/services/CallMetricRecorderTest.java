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

import io.grpc.Context;
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
    recorder.recordCallMetric("cost1", 154353.423);
    recorder.recordCallMetric("cost2", 0.1367);
    recorder.recordCallMetric("cost3", 1437.34);

    Map<String, Double> dump = recorder.finalizeAndDump();
    assertThat(dump)
        .containsExactly("cost1", 154353.423, "cost2", 0.1367, "cost3", 1437.34);
  }

  @Test
  public void noMetricsRecordedAfterSnapshot() {
    Map<String, Double> initDump = recorder.finalizeAndDump();
    recorder.recordCallMetric("cost", 154353.423);
    assertThat(recorder.finalizeAndDump()).isEqualTo(initDump);
  }

  @Test
  public void lastValueWinForMetricsWithSameName() {
    recorder.recordCallMetric("cost1", 3412.5435);
    recorder.recordCallMetric("cost2", 6441.341);
    recorder.recordCallMetric("cost1", 6441.341);
    recorder.recordCallMetric("cost1", 4654.67);
    recorder.recordCallMetric("cost2", 75.83);
    Map<String, Double> dump = recorder.finalizeAndDump();
    assertThat(dump)
        .containsExactly("cost1", 4654.67, "cost2", 75.83);
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
