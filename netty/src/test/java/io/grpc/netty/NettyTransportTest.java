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

import io.grpc.Attributes;
import io.grpc.Attributes.Key;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.testing.AbstractTransportTest;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;

/** Unit tests for Netty transport. */
@RunWith(JUnit4.class)
public class NettyTransportTest extends AbstractTransportTest {
  // Avoid LocalChannel for testing because LocalChannel can fail with
  // io.netty.channel.ChannelException instead of java.net.ConnectException which breaks
  // serverNotListening test.
  private ClientTransportFactory clientFactory = NettyChannelBuilder
      // Although specified here, address is ignored because we never call build.
      .forAddress("localhost", 0)
      .flowControlWindow(65 * 1024)
      .negotiationType(NegotiationType.PLAINTEXT)
      .buildTransportFactory();

  private Attributes transportAttrs;

  @After
  public void releaseClientFactory() {
    clientFactory.close();
  }

  @Override
  protected InternalServer newServer() {
    return NettyServerBuilder
        .forPort(0)
        .flowControlWindow(65 * 1024)
        .buildTransportServer();
  }

  @Override
  protected InternalServer newServer(InternalServer server) {
    int port = server.getPort();
    return NettyServerBuilder
        .forPort(port)
        .flowControlWindow(65 * 1024)
        .buildTransportServer();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    int port = server.getPort();
    NettyClientTransport transport = (NettyClientTransport) clientFactory.newClientTransport(
        new InetSocketAddress("localhost", port),
        "localhost:" + port,
        null /* agent */);
    if (transportAttrs != null) {
      transport.setAttrsForTest(transportAttrs);
    }
    return transport;
  }

  @Test
  @Ignore("flaky")
  @Override
  public void flowControlPushBack() {}

  @Test
  @Override
  public void clientStreamOnConnectionCalled() throws Exception {
    @SuppressWarnings("unchecked") // replace the wildcard type in APPLICATION_FILTER by Object type
    Key<Collection<Object>> filterKey =
        (Key<Collection<Object>>)((Key<?>) ConnectionClientTransport.APPLICATION_FILTER);
    transportAttrs = Attributes.newBuilder()
        .set(filterKey, Collections.singleton((Object) filterKey))
        .build();

    super.clientStreamOnConnectionCalled();
  }
}
