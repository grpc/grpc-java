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
    assertSnapshot(emptySnapshot, 0, 0, 0, 0);
  }

  @Test
  public void snapshotContainsEverything() {
    long numSucceededCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFailedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numIssuedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    counter =
        new ClientLoadCounter(numSucceededCalls, numInProgressCalls, numFailedCalls,
            numIssuedCalls);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertSnapshot(snapshot, numSucceededCalls, numInProgressCalls, numFailedCalls, numIssuedCalls);
    String snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsSucceeded=" + numSucceededCalls);
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=" + numFailedCalls);
    assertThat(snapshotStr).contains("callsIssued=" + numIssuedCalls);

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertSnapshot(snapshot, 0, numInProgressCalls, 0, 0);

    snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsSucceeded=0");
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=0");
    assertThat(snapshotStr).contains("callsIssued=0");
  }

  @Test
  public void normalCountingOperations() {
    counter.recordCallStarted();
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertSnapshot(snapshot, 0, 1, 0, 1);

    counter.recordCallFinished(Status.OK);
    snapshot = counter.snapshot();
    assertSnapshot(snapshot, 1, 0, 0, 0);

    counter.recordCallStarted();
    counter.recordCallFinished(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertSnapshot(snapshot, 0, 0, 1, 1);
  }

  @Test
  public void xdsClientLoadRecorder_clientSideQueryCountsAggregation() {
    XdsClientLoadRecorder recorder1 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertSnapshot(snapshot, 0, 1, 0, 1);
    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertSnapshot(snapshot, 1, 0, 0, 0);

    // Create a second XdsClientLoadRecorder with the same counter, stats are aggregated together.
    XdsClientLoadRecorder recorder2 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertSnapshot(snapshot, 0, 0, 2, 2);
  }

  private void assertSnapshot(ClientLoadSnapshot snapshot,
      long callsSucceeded,
      long callsInProgress,
      long callsFailed,
      long callsIssued) {
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(callsSucceeded);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(callsInProgress);
    assertThat(snapshot.getCallsFailed()).isEqualTo(callsFailed);
    assertThat(snapshot.getCallsIssued()).isEqualTo(callsIssued);
  }
}
