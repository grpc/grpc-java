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

import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.XdsClientLoadRecorder;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientLoadCounter}. */
@RunWith(JUnit4.class)
public class ClientLoadCounterTest {

  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();
  private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return new ClientStreamTracer() {
          };
        }
      };
  private ClientLoadCounter counter;

  @Before
  public void setUp() {
    counter = new ClientLoadCounter();
    ClientLoadSnapshot emptySnapshot = counter.snapshot();
    assertThat(emptySnapshot.callsInProgress).isEqualTo(0);
    assertThat(emptySnapshot.callsSucceed).isEqualTo(0);
    assertThat(emptySnapshot.callsFailed).isEqualTo(0);
  }

  @Test
  public void snapshotContainsEverything() {
    long numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFinishedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFailedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    counter = new ClientLoadCounter(numInProgressCalls, numFinishedCalls, numFailedCalls);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(numFinishedCalls - numFailedCalls);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(numFailedCalls);

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(0);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(0);
  }

  @Test
  public void normalCountingOperations() {
    ClientLoadSnapshot preSnapshot = counter.snapshot();
    counter.incrementCallsInProgress();
    ClientLoadSnapshot afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsInProgress).isEqualTo(preSnapshot.callsInProgress + 1);
    counter.decrementCallsInProgress();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsInProgress).isEqualTo(preSnapshot.callsInProgress);

    counter.incrementCallsFinished();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsSucceed).isEqualTo(1);

    counter.incrementCallsFinished();
    counter.incrementCallsFailed();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsFailed).isEqualTo(1);
    assertThat(afterSnapshot.callsSucceed).isEqualTo(0);
  }

  @Test
  public void xdsClientLoadRecorder_clientSideQueryCountsAggregation() {
    XdsClientLoadRecorder recorder1 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.callsInProgress).isEqualTo(1);
    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(1);
    assertThat(snapshot.callsInProgress).isEqualTo(0);

    // Create a second XdsClientLoadRecorder with the same counter, stats are aggregated together.
    XdsClientLoadRecorder recorder2 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertThat(snapshot.callsFailed).isEqualTo(2);
  }
}
