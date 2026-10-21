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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.stub.AbstractAsyncStubTest.NoopAsyncStub;
import io.grpc.stub.AbstractBlockingStubTest.NoopBlockingStub;
import io.grpc.stub.AbstractFutureStubTest.NoopFutureStub;
import io.grpc.stub.AbstractStub.StubFactory;
import io.grpc.stub.ClientCalls.StubType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AbstractAsyncStubTest extends BaseAbstractStubTest<NoopAsyncStub> {

  @Override
  NoopAsyncStub create(Channel channel, CallOptions callOptions) {
    return new NoopAsyncStub(channel, callOptions);
  }

  @Test
  public void defaultCallOptions() {
    NoopAsyncStub stub = NoopAsyncStub.newStub(new StubFactory<NoopAsyncStub>() {
      @Override
      public NoopAsyncStub newStub(Channel channel, CallOptions callOptions) {
        return create(channel, callOptions);
      }
    }, channel, CallOptions.DEFAULT);

    assertThat(stub.getCallOptions().getOption(ClientCalls.STUB_TYPE_OPTION))
        .isEqualTo(StubType.ASYNC);
  }

  @Test
  @SuppressWarnings("AssertionFailureIgnored")
  public void newStub_futureStub_throwsException() {
    try {
      NoopFutureStub unused = NoopAsyncStub.newStub(new StubFactory<NoopFutureStub>() {
        @Override
        public NoopFutureStub newStub(Channel channel, CallOptions callOptions) {
          return new NoopFutureStub(channel, callOptions);
        }
      }, channel, CallOptions.DEFAULT);
      fail("should not reach here");
    } catch (AssertionError e) {
      assertThat(e).hasMessageThat().startsWith("Expected AbstractAsyncStub");
    }
  }

  @Test
  @SuppressWarnings("AssertionFailureIgnored")
  public void newStub_blockingStub_throwsException() {
    try {
      NoopBlockingStub unused = NoopAsyncStub.newStub(new StubFactory<NoopBlockingStub>() {
        @Override
        public NoopBlockingStub newStub(Channel channel, CallOptions callOptions) {
          return new NoopBlockingStub(channel, callOptions);
        }
      }, channel, CallOptions.DEFAULT);
      fail("should not reach here");
    } catch (AssertionError e) {
      assertThat(e).hasMessageThat().startsWith("Expected AbstractAsyncStub");
    }
  }

  static class NoopAsyncStub extends AbstractAsyncStub<NoopAsyncStub> {

    NoopAsyncStub(Channel channel, CallOptions options) {
      super(channel, options);
    }

    @Override
    protected NoopAsyncStub build(Channel channel, CallOptions callOptions) {
      return new NoopAsyncStub(channel, callOptions);
    }
  }
}
