package io.grpc.stub;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.stub.ClientCalls.CallType;
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
    this(channel, CallOptions.DEFAULT);
  }

  protected AbstractBlockingStub(Channel channel, CallOptions callOptions) {
    super(channel, callOptions.withOption(ClientCalls.CALL_TYPE_OPTION, CallType.BLOCKING));
  }
}
