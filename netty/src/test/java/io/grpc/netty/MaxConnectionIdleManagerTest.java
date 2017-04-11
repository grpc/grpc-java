/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.internal.FakeClock;
import io.grpc.netty.MaxConnectionIdleManager.Ticker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Error;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link MaxConnectionIdleManager}. */
@RunWith(JUnit4.class)
public class MaxConnectionIdleManagerTest {
  private final FakeClock fakeClock = new FakeClock();
  private final Ticker ticker = new Ticker() {
    @Override
    public long nanoTime() {
      return fakeClock.getTicker().read();
    }
  };

  @Mock
  private NettyServerHandler handler;
  @Mock
  private ChannelHandlerContext ctx;


  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(ctx.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
  }

  @Test
  public void maxIdleReached() {
    MaxConnectionIdleManager maxConnectionIdleManager = new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.onHandlerAdded(handler, ctx, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(123L);

    verify(handler).goAway(
        eq(ctx), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), isA(ByteBuf.class),
        any(ChannelPromise.class));
  }

  @Test
  public void maxIdleNotReachedAndReached() {
    MaxConnectionIdleManager maxConnectionIdleManager = new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.onHandlerAdded(handler, ctx, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(100L);
    // max idle not reached
    maxConnectionIdleManager.onTransportActive();
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(100L);
    // max idle not reached although accumulative idle time exceeds max idle time
    maxConnectionIdleManager.onTransportActive();
    fakeClock.forwardNanos(100L);

    verify(handler, never()).goAway(
        any(ChannelHandlerContext.class), any(Integer.class), any(Long.class), any(ByteBuf.class),
        any(ChannelPromise.class));

    // max idle reached
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(123L);

    verify(handler).goAway(
        eq(ctx), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), isA(ByteBuf.class),
        any(ChannelPromise.class));
  }

  @Test
  public void shutdownThenMaxIdleReached() {
    MaxConnectionIdleManager maxConnectionIdleManager = new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.onHandlerAdded(handler, ctx, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    maxConnectionIdleManager.onTransportTermination();
    fakeClock.forwardNanos(123L);

    verify(handler, never()).goAway(
        any(ChannelHandlerContext.class), any(Integer.class), any(Long.class), any(ByteBuf.class),
        any(ChannelPromise.class));
  }
}
