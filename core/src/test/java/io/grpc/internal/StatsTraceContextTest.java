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

package io.grpc.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ClientStreamTracer;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StreamTracer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StatsTraceContext}. */
@RunWith(JUnit4.class)
public class StatsTraceContextTest {

  @Test
  public void clientCancelled_notifiesClientStreamTracers() {
    ClientStreamTracer clientTracer = mock(ClientStreamTracer.class);
    ServerStreamTracer serverTracer = mock(ServerStreamTracer.class);

    StatsTraceContext statsTraceCtx = new StatsTraceContext(
        new StreamTracer[] {clientTracer, serverTracer});

    Status cancelledStatus = Status.CANCELLED.withDescription("Client cancelled");
    statsTraceCtx.clientCancelled(cancelledStatus);

    verify(clientTracer).cancelled(cancelledStatus);
    verifyNoInteractions(serverTracer);
  }
}
