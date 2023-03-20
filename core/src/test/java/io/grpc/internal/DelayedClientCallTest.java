/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingTestUtil;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link DelayedClientCall}. */
@RunWith(JUnit4.class)
public class DelayedClientCallTest {
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock
  private ClientCall<String, Integer> mockRealCall;
  @Mock
  private ClientCall.Listener<Integer> listener;
  @Captor
  ArgumentCaptor<Status> statusCaptor;

  private final FakeClock fakeClock = new FakeClock();
  private final Executor callExecutor = MoreExecutors.directExecutor();

  @Test
  public void allMethodsForwarded() throws Exception {
    DelayedClientCall<String, Integer> delayedClientCall =
        new DelayedClientCall<>(callExecutor, fakeClock.getScheduledExecutorService(), null);
    callMeMaybe(delayedClientCall.setCall(mockRealCall));
    ForwardingTestUtil.testMethodsForwarded(
        ClientCall.class,
        mockRealCall,
        delayedClientCall,
        Arrays.asList(ClientCall.class.getMethod("toString"),
            ClientCall.class.getMethod("start", Listener.class, Metadata.class)),
        new ForwardingTestUtil.ArgumentProvider() {
          @Override
          public Object get(Method method, int argPos, Class<?> clazz) {
            if (!Modifier.isFinal(clazz.getModifiers())) {
              return mock(clazz);
            }
            if (clazz.equals(String.class)) {
              return "message";
            }
            return null;
          }
        });
  }

  // Coverage for deadline exceeded before call started is enforced by
  // AbstractInteropTest.deadlineInPast().
  @Test
  public void deadlineExceededWhileCallIsStartedButStillPending() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), Deadline.after(10, SECONDS));

    delayedClientCall.start(listener, new Metadata());
    fakeClock.forwardTime(10, SECONDS);
    verify(listener).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
  }

  @Test
  public void listenerEventsPropagated() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), Deadline.after(10, SECONDS));
    delayedClientCall.start(listener, new Metadata());
    callMeMaybe(delayedClientCall.setCall(mockRealCall));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Listener<Integer>> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
    verify(mockRealCall).start(listenerCaptor.capture(), any(Metadata.class));
    Listener<Integer> realCallListener = listenerCaptor.getValue();
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("key", Metadata.ASCII_STRING_MARSHALLER), "value");
    realCallListener.onHeaders(metadata);
    verify(listener).onHeaders(metadata);
    realCallListener.onMessage(3);
    verify(listener).onMessage(3);
    realCallListener.onReady();
    verify(listener).onReady();
    Metadata trailer = new Metadata();
    trailer.put(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER), "value2");
    realCallListener.onClose(Status.DATA_LOSS, trailer);
    verify(listener).onClose(statusCaptor.capture(), eq(trailer));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.DATA_LOSS);
  }

  @Test
  public void setCallThenStart() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), null);
    callMeMaybe(delayedClientCall.setCall(mockRealCall));
    delayedClientCall.start(listener, new Metadata());
    delayedClientCall.request(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Listener<Integer>> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
    verify(mockRealCall).start(listenerCaptor.capture(), any(Metadata.class));
    Listener<Integer> realCallListener = listenerCaptor.getValue();
    verify(mockRealCall).request(1);
    realCallListener.onMessage(1);
    verify(listener).onMessage(1);
  }

  @Test
  public void startThenSetCall() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), null);
    delayedClientCall.start(listener, new Metadata());
    delayedClientCall.request(1);
    Runnable r = delayedClientCall.setCall(mockRealCall);
    assertThat(r).isNotNull();
    r.run();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Listener<Integer>> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
    verify(mockRealCall).start(listenerCaptor.capture(), any(Metadata.class));
    Listener<Integer> realCallListener = listenerCaptor.getValue();
    verify(mockRealCall).request(1);
    realCallListener.onMessage(1);
    verify(listener).onMessage(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cancelThenSetCall() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), null);
    delayedClientCall.start(listener, new Metadata());
    delayedClientCall.request(1);
    delayedClientCall.cancel("cancel", new StatusException(Status.CANCELLED));
    Runnable r = delayedClientCall.setCall(mockRealCall);
    assertThat(r).isNull();
    verify(mockRealCall, never()).start(any(Listener.class), any(Metadata.class));
    verify(mockRealCall, never()).request(1);
    verify(mockRealCall, never()).cancel(any(), any());
    verify(listener).onClose(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void setCallThenCancel() {
    DelayedClientCall<String, Integer> delayedClientCall = new DelayedClientCall<>(
        callExecutor, fakeClock.getScheduledExecutorService(), null);
    delayedClientCall.start(listener, new Metadata());
    delayedClientCall.request(1);
    Runnable r = delayedClientCall.setCall(mockRealCall);
    assertThat(r).isNotNull();
    r.run();
    delayedClientCall.cancel("cancel", new StatusException(Status.CANCELLED));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Listener<Integer>> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
    verify(mockRealCall).start(listenerCaptor.capture(), any(Metadata.class));
    Listener<Integer> realCallListener = listenerCaptor.getValue();
    verify(mockRealCall).request(1);
    verify(mockRealCall).cancel(any(), any());
    realCallListener.onClose(Status.CANCELLED, null);
    verify(listener).onClose(Status.CANCELLED, null);
  }

  @Test
  public void delayedCallsRunUnderContext() throws Exception {
    Context.Key<Object> contextKey = Context.key("foo");
    Object goldenValue = new Object();
    DelayedClientCall<String, Integer> delayedClientCall =
        Context.current().withValue(contextKey, goldenValue).call(() ->
          new DelayedClientCall<>(callExecutor, fakeClock.getScheduledExecutorService(), null));
    AtomicReference<Context> readyContext = new AtomicReference<>();
    delayedClientCall.start(new ClientCall.Listener<Integer>() {
      @Override public void onReady() {
        readyContext.set(Context.current());
      }
    }, new Metadata());
    AtomicReference<Context> startContext = new AtomicReference<>();
    Runnable r = delayedClientCall.setCall(new SimpleForwardingClientCall<String, Integer>(
        mockRealCall) {
      @Override public void start(Listener<Integer> listener, Metadata metadata) {
        startContext.set(Context.current());
        listener.onReady(); // Delayed until call finishes draining
        assertThat(readyContext.get()).isNull();
        super.start(listener, metadata);
      }
    });
    assertThat(r).isNotNull();
    r.run();
    assertThat(contextKey.get(startContext.get())).isEqualTo(goldenValue);
    assertThat(contextKey.get(readyContext.get())).isEqualTo(goldenValue);
  }

  private void callMeMaybe(Runnable r) {
    if (r != null) {
      r.run();
    }
  }
}
