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
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

import io.grpc.NameResolver;
import io.grpc.NameResolver.Listener2;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.internal.RetryingNameResolver.ResolutionResultListener;
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
  private ArgumentCaptor<ResolutionResult> resolutionResultCaptor;

  private RetryingNameResolver retryingNameResolver;

  @Before
  public void setup() {
    retryingNameResolver = new RetryingNameResolver(mockNameResolver, mockRetryScheduler);
  }

  @Test
  public void startAndShutdown() {
    retryingNameResolver.start(mockListener);
    retryingNameResolver.shutdown();
  }

  // Make sure the ResolutionResultListener callback is added to the ResolutionResult attributes,
  // and the retry scheduler is reset since the name resolution was successful..
  @Test
  public void onResult_sucess() {
    retryingNameResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());

    listenerCaptor.getValue().onResult(ResolutionResult.newBuilder().build());
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResultListener resolutionResultListener = resolutionResultCaptor.getValue()
        .getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY);
    assertThat(resolutionResultListener).isNotNull();

    resolutionResultListener.resolutionAttempted(true);
    verify(mockRetryScheduler).reset();
  }

  // Make sure the ResolutionResultListener callback is added to the ResolutionResult attributes,
  // and the retry scheduler is reset since the name resolution was successful..
  @Test
  public void onResult_failure() {
    retryingNameResolver.start(mockListener);
    verify(mockNameResolver).start(listenerCaptor.capture());

    listenerCaptor.getValue().onResult(ResolutionResult.newBuilder().build());
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResultListener resolutionResultListener = resolutionResultCaptor.getValue()
        .getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY);
    assertThat(resolutionResultListener).isNotNull();

    resolutionResultListener.resolutionAttempted(false);
    verify(mockRetryScheduler).schedule(isA(Runnable.class));
  }

  @Test
  public void onError() {

  }
}