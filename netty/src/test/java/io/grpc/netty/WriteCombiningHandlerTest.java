/*
 * Copyright 2016, Google Inc. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;

/**
 * Tests for {@link WriteCombiningHandler}.
 */
public class WriteCombiningHandlerTest {

  private EmbeddedChannel channel;

  @Before
  public void setup() throws InterruptedException {
    channel = new EmbeddedChannel();
    channel.connect(new InetSocketAddress(0)).sync();
    channel.pipeline().addLast(new WriteCombiningHandler());
  }

  @Test
  public void basicWriteCombining() {
    ChannelPromise p1 = channel.newPromise();
    ChannelPromise p2 = channel.newPromise();
    ChannelPromise p3 = channel.newPromise();
    ChannelPromise p4 = channel.newPromise();

    channel.write(buf(50), p1);
    channel.write(buf(5), p2);
    channel.write(buf(95), p3);
    assertNull(channel.readOutbound());
    channel.flush();
    channel.write(buf(10), p4);

    ByteBuf combined = channel.readOutbound();
    assertNull(channel.readOutbound());
    assertEquals(150, combined.readableBytes());
    assertTrue(p1.isSuccess());
    assertTrue(p2.isSuccess());
    assertTrue(p3.isSuccess());
    assertFalse(p4.isSuccess());

    channel.flush();
    combined = channel.readOutbound();
    assertEquals(10, combined.readableBytes());
    assertTrue(p4.isSuccess());
  }

  @Test
  public void largeWritesShouldNotBeCombined() {
    ChannelPromise p1 = channel.newPromise();
    ChannelPromise p2 = channel.newPromise();
    channel.write(buf(1024), p1);
    channel.write(buf(2048), p2);
    channel.flush();
    ByteBuf b = channel.readOutbound();
    assertEquals(1024, b.readableBytes());
    assertTrue(p1.isSuccess());
    b = channel.readOutbound();
    assertEquals(2048, b.readableBytes());
    assertTrue(p2.isSuccess());
  }

  @Test
  public void mixingLargeAndCombinedWritesShouldWork() {
    ChannelPromise p1 = channel.newPromise();
    ChannelPromise p2 = channel.newPromise();
    ChannelPromise p3 = channel.newPromise();
    ChannelPromise p4 = channel.newPromise();

    channel.write(buf(10), p1);
    channel.write(buf(100), p2);
    channel.write(buf(1024), p3);
    channel.write(buf(10), p4);
    channel.flush();

    ByteBuf b = channel.readOutbound();
    assertEquals(110, b.readableBytes());
    b = channel.readOutbound();
    assertEquals(1024, b.readableBytes());
    b = channel.readOutbound();
    assertEquals(10, b.readableBytes());

    assertTrue(p1.isSuccess());
    assertTrue(p2.isSuccess());
    assertTrue(p3.isSuccess());
    assertTrue(p4.isSuccess());
  }

  ByteBuf buf(int size) {
    return channel.alloc().directBuffer(size, size).writeZero(size);
  }
}
