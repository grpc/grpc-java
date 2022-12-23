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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.stub.ClientCalls.StubType;
import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Stub implementations for blocking stubs.
 *
 * <p>DO NOT MOCK: Customizing options doesn't work properly in mocks. Use InProcessChannelBuilder
 * to create a real channel suitable for testing. It is also possible to mock Channel instead.
 *
 * @since 1.26.0
 */
@ThreadSafe
@CheckReturnValue
public abstract class AbstractBlockingStub<S extends AbstractBlockingStub<S>>
    extends AbstractStub<S> {

  protected AbstractBlockingStub(Channel channel, CallOptions callOptions) {
    super(channel, callOptions);
  }

  /**
   * Returns a new blocking stub with the given channel for the provided method configurations.
   *
   * @since 1.26.0
   * @param factory the factory to create a blocking stub
   * @param channel the channel that this stub will use to do communications
   */
  public static <T extends AbstractStub<T>> T newStub(
      StubFactory<T> factory, Channel channel) {
    return newStub(factory, channel, CallOptions.DEFAULT);
  }

  /**
   * Returns a new blocking stub with the given channel for the provided method configurations.
   *
   * @since 1.26.0
   * @param factory the factory to create a blocking stub
   * @param channel the channel that this stub will use to do communications
   * @param callOptions the runtime call options to be applied to every call on this stub
   */
  public static <T extends AbstractStub<T>> T newStub(
      StubFactory<T> factory, Channel channel, CallOptions callOptions) {
    T stub = factory.newStub(
        channel, callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING));
    assert stub instanceof AbstractBlockingStub
        : String.format("Expected AbstractBlockingStub, but got %s.", stub.getClass());
    return stub;
  }
}
