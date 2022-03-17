/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability.logging;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.util.Collection;
import java.util.Iterator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link io.grpc.observability.logging.GcpLogSink}.
 */
@RunWith(JUnit4.class)
public class GcpLogSinkTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private Logging mockLogging;

  @Before
  public void setUp() {
    mockLogging = mock(Logging.class);
  }

  @Test
  public void createSink() {
    Sink mockSink = new GcpLogSink(mockLogging);
    assertThat(mockSink).isInstanceOf(GcpLogSink.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWrite() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging);
    GrpcLogRecord logProto = GrpcLogRecord.newBuilder()
        .setRpcId("1234")
        .build();
    Struct expectedStructLogProto = Struct.newBuilder().putFields(
        "rpc_id", Value.newBuilder().setStringValue("1234").build()
    ).build();

    mockSink.write(logProto);
    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertEquals(entry.getPayload().getData(), expectedStructLogProto);
    }
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  public void verifyClose() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging);
    GrpcLogRecord logProto = GrpcLogRecord.newBuilder()
        .setRpcId("1234")
        .build();
    mockSink.write(logProto);
    verify(mockLogging, times(1)).write(anyIterable());
    mockSink.close();
    verify(mockLogging).close();
    verifyNoMoreInteractions(mockLogging);
  }
}
