/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.alts.internal;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.alts.internal.TsiHandshakeHandler.HandshakeValidator;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TsiHandshakeHandlerTest {
  private final AsyncSemaphore semaphore = new AsyncSemaphore(1);
  private final AltsHandshakerStub stub = mock(AltsHandshakerStub.class);
  private final NoopChannelLogger logger = new NoopChannelLogger();
  private final AltsHandshakerClient client =
      new AltsHandshakerClient(stub, new AltsHandshakerOptions(null), logger);
  private final HandshakeValidator validator = mock(HandshakeValidator.class);
  private Throwable failure;
  private final EmbeddedChannel channel = new EmbeddedChannel(
      new TsiHandshakeHandler(new ChannelInboundHandlerAdapter(),
          new NettyTsiHandshaker(new AltsTsiHandshaker(true, client, logger)),
          validator, semaphore, logger),
      new ChannelInboundHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          failure = cause;
          // The transport's WriteBufferingAndExceptionHandler closes on negotiation failure.
          ctx.close();
        }
      });
  private final ChannelHandlerContext ctx = channel.pipeline().firstContext();

  @After
  public void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  public void handshakeCompletesBeforeTimeoutAndReturnsPermit() throws Exception {
    byte[] peerFrame = MockAltsHandshakerResp.getOutFrame().toByteArray();
    when(stub.send(any())).thenReturn(MockAltsHandshakerResp.getOkResponse(0),
        MockAltsHandshakerResp.getFinishedResponse(peerFrame.length));
    when(validator.validatePeerObject(any())).thenReturn(
        new HandshakeValidator.SecurityDetails(SecurityLevel.PRIVACY_AND_INTEGRITY, null));

    startHandshake();
    ChannelFuture next = semaphore.acquire(ctx);
    assertThat(next.isDone()).isFalse();
    channel.advanceTimeBy(TsiHandshakeHandler.DEFAULT_HANDSHAKE_TIMEOUT_SECONDS - 1, SECONDS);
    // Simulate receiving the peer's handshake frame before the deadline.
    channel.writeInbound(Unpooled.wrappedBuffer(peerFrame));

    assertThat(channel.pipeline().get(TsiFrameHandler.class)).isNotNull();
    assertThat(next.isSuccess()).isTrue();
    channel.advanceTimeBy(1, SECONDS);
    channel.runScheduledPendingTasks();
    assertThat(failure).isNull();
    assertThat(channel.isActive()).isTrue();
    // The next waiter holds the only permit, so another acquire must remain pending.
    assertThat(semaphore.acquire(ctx).isDone()).isFalse();
  }

  @Test
  public void handshakeTimeoutClosesChannelAndReturnsPermit() throws Exception {
    when(stub.send(any())).thenReturn(MockAltsHandshakerResp.getOkResponse(0));
    // Simulate another connection holding the only permit while this handshake waits.
    assertThat(semaphore.acquire(ctx).isSuccess()).isTrue();
    startHandshake();
    channel.advanceTimeBy(TsiHandshakeHandler.DEFAULT_HANDSHAKE_TIMEOUT_SECONDS - 1, SECONDS);

    // The other connection releases its permit, so this handshake can send its first frame.
    semaphore.release();
    channel.runPendingTasks();
    assertThat(channel.outboundMessages()).hasSize(1);

    // Queue a waiter to observe when this handshake returns the permit.
    ChannelFuture next = semaphore.acquire(ctx);
    assertThat(next.isDone()).isFalse();

    // The peer never replies. Only one second remains because queuing counts toward the timeout.
    channel.advanceTimeBy(1, SECONDS);
    channel.runScheduledPendingTasks();

    assertThat(failure).isNotNull();
    Status status = Status.fromThrowable(failure);
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status.getDescription()).isEqualTo("ALTS handshake timed out");
    assertThat(channel.isOpen()).isFalse();
    // Closing the channel hands the only permit to the waiter. A further acquire must wait.
    assertThat(next.isSuccess()).isTrue();
    // The next waiter holds the only permit, so another acquire must remain pending.
    assertThat(semaphore.acquire(ctx).isDone()).isFalse();
  }

  private void startHandshake() {
    channel.freezeTime();
    channel.pipeline().fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
  }
}
