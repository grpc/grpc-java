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
 * Stub implementations for blocking stubs. Stub configuration is immutable; changing the
 * configuration returns a new stub with updated configuration. Changing the configuration is cheap
 * and may be done before every RPC, such as would be common when using {@link #withDeadlineAfter}.
 *
 * <p>Configuration is stored in {@link CallOptions} and is passed to the {@link Channel} when
 * performing an RPC.
 *
 * <p>DO NOT MOCK: Customizing options doesn't work properly in mocks. Use InProcessChannelBuilder
 * to create a real channel suitable for testing. It is also possible to mock Channel instead.
 *
 * @since 1.25.0
 */
@ThreadSafe
@CheckReturnValue
public abstract class AbstractBlockingStub<S extends AbstractBlockingStub<S>>
    extends AbstractStub<S> {

  protected AbstractBlockingStub(Channel channel) {
    this(channel, CallOptions.DEFAULT, new BlockingCallOptionsFactory());
  }

  protected AbstractBlockingStub(
      Channel channel, CallOptions callOptions, DefaultCallOptionsFactory factory) {
    super(channel, callOptions, factory);
  }

  /** A BlockingCallOptionsFactory adds default call options for BlockingStub. */
  protected static final class BlockingCallOptionsFactory implements DefaultCallOptionsFactory {

    @Override
    public CallOptions create(CallOptions providedCallOptions) {
      return providedCallOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING);
    }
  }
}
