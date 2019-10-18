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

package io.grpc.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.CallOptions;
import io.grpc.Channel;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Standard unit tests for AbstractStub and its subclasses. */
abstract class BaseAbstractStubTest<T extends AbstractStub<T>> {

  @Mock
  Channel channel;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  T create(Channel channel) {
    return create(channel, CallOptions.DEFAULT);
  }

  abstract T create(@Nullable Channel channel, @Nullable CallOptions callOptions);

  @Test
  public void callOptionsMustNotBeNull() {
    try {
      create(channel, null);
      fail("NullPointerException expected");
    } catch (NullPointerException npe) {
      // expected
    }
  }

  @Test
  public void channelMustNotBeNull2() {
    try {
      create(null, CallOptions.DEFAULT);
      fail("NullPointerException expected");
    } catch (NullPointerException npe) {
      // expected
    }
  }

  @Test
  public void withWaitForReady() {
    T stub = create(channel);
    CallOptions callOptions = stub.getCallOptions();
    assertFalse(callOptions.isWaitForReady());

    stub = stub.withWaitForReady();
    callOptions = stub.getCallOptions();
    assertTrue(callOptions.isWaitForReady());
  }

  @Test
  public void withExecutor() {
    T stub = create(channel);
    CallOptions callOptions = stub.getCallOptions();

    assertNull(callOptions.getExecutor());

    Executor executor = mock(Executor.class);
    stub = stub.withExecutor(executor);
    callOptions = stub.getCallOptions();

    assertEquals(callOptions.getExecutor(), executor);
  }
}
