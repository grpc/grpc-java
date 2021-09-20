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
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.Deadline;
import io.grpc.ForwardingTestUtil;
import io.grpc.Metadata;
import io.grpc.Status;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.Executor;
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
    delayedClientCall.setCall(mockRealCall);
    ForwardingTestUtil.testMethodsForwarded(
        ClientCall.class,
        mockRealCall,
        delayedClientCall,
        Arrays.asList(ClientCall.class.getMethod("toString")),
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
    delayedClientCall.setCall(mockRealCall);
    ArgumentCaptor<Listener<Integer>> listenerCaptor = ArgumentCaptor.forClass(null);
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
}
