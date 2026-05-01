/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.binder.internal.SimplePromise.Listener;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class SimplePromiseTest {

  private static final String FULFILLED_VALUE = "a fulfilled value";

  @Mock private Listener<String> mockListener1;
  @Mock private Listener<String> mockListener2;
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private SimplePromise<String> promise = new SimplePromise<>();

  @Before
  public void setUp() {
  }

  @Test
  public void get_beforeFulfilled_throws() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> promise.get());
    assertThat(e).hasMessageThat().isEqualTo("Not yet set!");
  }

  @Test
  public void get_afterFulfilled_returnsValue() {
    promise.set(FULFILLED_VALUE);
    assertThat(promise.get()).isEqualTo(FULFILLED_VALUE);
  }

  @Test
  public void set_withNull_throws() {
    assertThrows(NullPointerException.class, () -> promise.set(null));
  }

  @Test
  public void set_calledTwice_throws() {
    promise.set(FULFILLED_VALUE);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> promise.set("another value"));
    assertThat(e).hasMessageThat().isEqualTo("Already set!");
  }

  @Test
  public void runWhenSet_beforeFulfill_listenerIsNotifiedUponSet() {
    promise.runWhenSet(mockListener1);

    // Should not have been called yet.
    verify(mockListener1, never()).notify(FULFILLED_VALUE);

    promise.set(FULFILLED_VALUE);

    // Now it should be called.
    verify(mockListener1, times(1)).notify(FULFILLED_VALUE);
  }

  @Test
  public void runWhenSet_afterSet_listenerIsNotifiedImmediately() {
    promise.set(FULFILLED_VALUE);
    promise.runWhenSet(mockListener1);

    // Should have been called immediately.
    verify(mockListener1, times(1)).notify(FULFILLED_VALUE);
  }

  @Test
  public void multipleListeners_addedBeforeSet_allNotifiedInOrder() {
    promise.runWhenSet(mockListener1);
    promise.runWhenSet(mockListener2);

    promise.set(FULFILLED_VALUE);

    InOrder inOrder = inOrder(mockListener1, mockListener2);
    inOrder.verify(mockListener1).notify(FULFILLED_VALUE);
    inOrder.verify(mockListener2).notify(FULFILLED_VALUE);
  }

  @Test
  public void listenerThrows_duringSet_propagatesException() {
    // A listener that will throw when notified.
    Listener<String> throwingListener =
        (value) -> {
          throw new UnsupportedOperationException("Listener failed");
        };

    promise.runWhenSet(throwingListener);

    // Fulfilling the promise should now throw the exception from the listener.
    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> promise.set(FULFILLED_VALUE));
    assertThat(e).hasMessageThat().isEqualTo("Listener failed");
  }

  @Test
  public void listenerThrows_whenAddedAfterSet_propagatesException() {
    promise.set(FULFILLED_VALUE);

    // A listener that will throw when notified.
    Listener<String> throwingListener =
        (value) -> {
          throw new UnsupportedOperationException("Listener failed");
        };

    // Running the listener should throw immediately because the promise is already fulfilled.
    UnsupportedOperationException e =
        assertThrows(
            UnsupportedOperationException.class, () -> promise.runWhenSet(throwingListener));
    assertThat(e).hasMessageThat().isEqualTo("Listener failed");
  }
}
