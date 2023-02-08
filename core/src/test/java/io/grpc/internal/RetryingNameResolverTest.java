/*
 * Copyright 2023 The gRPC Authors
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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.NameResolver;
import io.grpc.NameResolver.Listener2;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.RetryingNameResolver.ResolutionResultListener;
import java.lang.Thread.UncaughtExceptionHandler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit test for {@link RetryingNameResolver}.
 */
@RunWith(JUnit4.class)
public class RetryingNameResolverTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private NameResolver mockNameResolver;
  @Mock
  private Listener2 mockListener;
  @Mock
  private RetryScheduler mockRetryScheduler;
  @Captor
  private ArgumentCaptor<Listener2> listenerCaptor;
  @Captor
  private ArgumentCaptor<ResolutionResult> onResultCaptor;
  private final SynchronizationContext syncContext = new SynchronizationContext(
      mock(UncaughtExceptionHandler.class));

  private RetryingNameResolver retryingNameResolver;

  @Before
  public void setup() {
    retryingNameResolver = new RetryingNameResolver(mockNameResolver, mockRetryScheduler,
        syncContext);
  }

  @Test
  public void startAndShutdown() {
    retryingNameResolver.start(mockListener);
    retryingNameResolver.shutdown();
  }

  // Make sure the ResolutionResultListener callback is added to the ResolutionResult attributes,
  // and the retry scheduler is reset since the name resolution was successful.
  @Test
  public void onResult_sucess() {
    retryingNameResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());

    listenerCaptor.getValue().onResult(ResolutionResult.newBuilder().build());
    verify(mockListener).onResult(onResultCaptor.capture());
    ResolutionResultListener resolutionResultListener = onResultCaptor.getValue()
        .getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY);
    assertThat(resolutionResultListener).isNotNull();

    resolutionResultListener.resolutionAttempted(true);
    verify(mockRetryScheduler).reset();
  }

  // Make sure the ResolutionResultListener callback is added to the ResolutionResult attributes,
  // and that a retry gets scheduled when the resolution results are rejected.
  @Test
  public void onResult_failure() {
    retryingNameResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());

    listenerCaptor.getValue().onResult(ResolutionResult.newBuilder().build());
    verify(mockListener).onResult(onResultCaptor.capture());
    ResolutionResultListener resolutionResultListener = onResultCaptor.getValue()
        .getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY);
    assertThat(resolutionResultListener).isNotNull();

    resolutionResultListener.resolutionAttempted(false);
    verify(mockRetryScheduler).schedule(isA(Runnable.class));
  }

  // Wrapping a NameResolver more than once is a misconfiguration.
  @Test
  public void onResult_failure_doubleWrapped() {
    NameResolver doubleWrappedResolver = new RetryingNameResolver(retryingNameResolver,
        mockRetryScheduler, syncContext);

    doubleWrappedResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());

    try {
      listenerCaptor.getValue().onResult(ResolutionResult.newBuilder().build());
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("can only be used once");
      return;
    }
    fail("An exception should have been thrown for a double wrapped NAmeResolver");
  }

  // A retry should get scheduled when name resolution fails.
  @Test
  public void onError() {
    retryingNameResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());
    listenerCaptor.getValue().onError(Status.DEADLINE_EXCEEDED);
    verify(mockListener).onError(Status.DEADLINE_EXCEEDED);
    verify(mockRetryScheduler).schedule(isA(Runnable.class));
  }
}