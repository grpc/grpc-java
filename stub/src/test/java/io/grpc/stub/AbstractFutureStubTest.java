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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.stub.AbstractFutureStubTest.NoopFutureStub;
import io.grpc.stub.AbstractStub.StubFactory;
import io.grpc.stub.ClientCalls.StubType;
import org.junit.Test;

public class AbstractFutureStubTest extends BaseAbstractStubTest<NoopFutureStub> {

  @Override
  NoopFutureStub create(Channel channel, CallOptions callOptions) {
    return new NoopFutureStub(channel, callOptions);
  }

  @Test
  public void defaultCallOptions() {
    NoopFutureStub stub = NoopFutureStub.newStub(new StubFactory<NoopFutureStub>() {
      @Override
      public NoopFutureStub newStub(Channel channel, CallOptions callOptions) {
        return create(channel, callOptions);
      }
    }, channel, CallOptions.DEFAULT);

    assertThat(stub.getCallOptions().getOption(ClientCalls.STUB_TYPE_OPTION))
        .isEqualTo(StubType.FUTURE);
  }

  class NoopFutureStub extends AbstractFutureStub<NoopFutureStub> {

    NoopFutureStub(Channel channel, CallOptions options) {
      super(channel, options);
    }

    @Override
    protected NoopFutureStub build(Channel channel, CallOptions callOptions) {
      return new NoopFutureStub(channel, callOptions);
    }
  }
}