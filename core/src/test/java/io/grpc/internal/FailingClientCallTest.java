/*
 * Copyright 2016 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link FailingClientCall}. */
@RunWith(JUnit4.class)
public class FailingClientCallTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private ClientCall.Listener<Object> mockListener;

  @Test
  public void startCallsOnClose() {
    Status error = Status.UNAVAILABLE.withDescription("test error");
    FailingClientCall<Object, Object> call = new FailingClientCall<>(error);
    Metadata metadata = new Metadata();
    call.start(mockListener, metadata);

    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(mockListener).onClose(eq(error), metadataCaptor.capture());
    assertEquals(0, metadataCaptor.getValue().keys().size());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void otherMethodsAreNoOps() {
    Status error = Status.UNAVAILABLE.withDescription("test error");
    FailingClientCall<Object, Object> call = new FailingClientCall<>(error);
    Metadata metadata = new Metadata();

    call.start(mockListener, metadata); // Must call start first

    call.request(1);
    call.cancel("message", new RuntimeException("cause"));
    call.halfClose();
    call.sendMessage(new Object());

    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(mockListener).onClose(eq(error), metadataCaptor.capture());
    assertEquals(0, metadataCaptor.getValue().keys().size());
    verifyNoMoreInteractions(mockListener);
  }
}
