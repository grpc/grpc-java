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

package io.grpc.android;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import com.google.common.testing.EqualsTester;
import java.net.InetSocketAddress;
import java.net.SocketException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class UdsSocketTest {

  private final UdsSocket socket =
      new UdsSocket.Client(new LocalSocketAddress("uds-socket-test", Namespace.ABSTRACT));

  /**
   * grpc-okhttp's channelz support ({@code Utils.getSocketOptions}) reads these options and logs
   * {@code Level.SEVERE} if any getter throws {@code SocketException} (while {@code
   * UnsupportedOperationException} escapes it outright), so each must return a benign value.
   */
  @Test
  public void socketOptionGetters_areChannelzSafe() {
    assertThat(socket.getKeepAlive()).isFalse();
    assertThat(socket.getOOBInline()).isFalse();
    assertThat(socket.getReuseAddress()).isFalse();
    assertThat(socket.getSoLinger()).isEqualTo(-1);
    assertThat(socket.getTcpNoDelay()).isTrue();
    assertThat(socket.getTrafficClass()).isEqualTo(0);
  }

  @Test
  public void acceptedSocket_getLocalSocketAddress_returnsListenAddress() {
    UdsSocketAddress listenAddress = UdsSocketAddress.forAbstractName("uds-socket-test");
    UdsSocket.Accepted accepted =
        new UdsSocket.Accepted(
            new LocalSocket(), listenAddress, new UdsPeerSocketAddress(10187, 10188, 4242));

    assertThat(accepted.getLocalSocketAddress()).isEqualTo(listenAddress);
  }

  @Test
  public void acceptedSocket_getRemoteSocketAddress_returnsPeerAddress() {
    UdsPeerSocketAddress peerAddress = new UdsPeerSocketAddress(10187, 10188, 4242);
    UdsSocket.Accepted accepted =
        new UdsSocket.Accepted(
            new LocalSocket(), UdsSocketAddress.forAbstractName("test"), peerAddress);

    assertThat(accepted.getRemoteSocketAddress()).isEqualTo(peerAddress);
    assertThat(accepted.getRemoteSocketAddress().toString())
        .isEqualTo("unix-peer:uid=10187,gid=10188,pid=4242");
    new EqualsTester()
        .addEqualityGroup(peerAddress, new UdsPeerSocketAddress(10187, 10188, 4242))
        .addEqualityGroup(new UdsPeerSocketAddress(10189, 10188, 4242))
        .addEqualityGroup(new UdsPeerSocketAddress(10187, 10189, 4242))
        .addEqualityGroup(new UdsPeerSocketAddress(10187, 10188, 4243))
        .testEquals();
  }

  @Test
  public void acceptedSocket_connect_throwsSocketException() {
    UdsSocket.Accepted accepted =
        new UdsSocket.Accepted(
            new LocalSocket(),
            UdsSocketAddress.forAbstractName("test"),
            new UdsPeerSocketAddress(10187, 10188, 4242));

    assertThrows(SocketException.class, () -> accepted.connect(new InetSocketAddress(0)));
    assertThrows(SocketException.class, () -> accepted.connect(new InetSocketAddress(0), 1000));
  }
}
