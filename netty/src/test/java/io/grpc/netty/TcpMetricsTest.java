/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.netty;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import java.util.Collections;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class TcpMetricsTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private MetricRecorder metricRecorder;
  @Mock private Channel channel;

  private TcpMetrics.Tracker metrics;

  @Before
  public void setUp() {
    metrics = new TcpMetrics.Tracker(metricRecorder, "target1");
  }

  @Test
  public void channelActive_incrementsCounts() {
    metrics.channelActive();
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.connectionsCreated), eq(1L), eq(Collections.singletonList("target1")),
        eq(Collections.emptyList()));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(1L), eq(Collections.singletonList("target1")),
        eq(Collections.emptyList()));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_decrementsCount_noEpoll_noError() {
    metrics.channelInactive(channel);
    // It should decrement connectionCount
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(-1L), eq(Collections.singletonList("target1")),
        eq(Collections.emptyList()));
    verifyNoMoreInteractions(metricRecorder);
  }
}
