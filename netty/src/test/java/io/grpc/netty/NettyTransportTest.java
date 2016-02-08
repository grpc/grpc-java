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

import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.Server;
import io.grpc.internal.testing.AbstractTransportTest;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Netty transport. */
@RunWith(JUnit4.class)
public class NettyTransportTest extends AbstractTransportTest {
  private static final String TRANSPORT_NAME = "perfect-for-testing";
  private ClientTransportFactory clientFactory = NettyChannelBuilder
      // Although specified here, address is ignored because we never call build.
      .forAddress(new LocalAddress(TRANSPORT_NAME))
      .flowControlWindow(65 * 1024)
      .negotiationType(NegotiationType.PLAINTEXT)
      .channelType(LocalChannel.class)
      .buildTransportFactory();

  @After
  public void releaseClientFactory() {
    clientFactory.release();
    assertEquals(0, clientFactory.referenceCount());
  }

  @Override
  protected Server newServer() {
    return NettyServerBuilder
        .forAddress(new LocalAddress(TRANSPORT_NAME))
        .flowControlWindow(65 * 1024)
        .channelType(LocalServerChannel.class)
        .buildTransportServer();
  }

  @Override
  protected ManagedClientTransport newClientTransport() {
    return clientFactory.newClientTransport(new LocalAddress(TRANSPORT_NAME), TRANSPORT_NAME);
  }

  // TODO(ejona): Netty fails to use UNAVAILABLE for the status.
  @Test
  @Ignore
  @Override
  public void serverNotListening() {}

  // TODO(ejona): Flaky
  @Test
  @Ignore
  @Override
  public void flowControlPushBack() {}
}
