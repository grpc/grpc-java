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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** On-device tests for {@link UdsServerSocket}'s {@link java.net.ServerSocket} state machine. */
@RunWith(AndroidJUnit4.class)
public final class UdsServerSocketTest {
  private final List<Closeable> closeables = new ArrayList<>();

  @After
  public void tearDown() {
    for (int i = closeables.size() - 1; i >= 0; i--) {
      try {
        closeables.get(i).close();
      } catch (IOException ignored) {
        // Best-effort test cleanup.
      }
    }
  }

  @Test
  public void bind_inetSocketAddress_throwsIllegalArgumentException() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());

    assertThrows(IllegalArgumentException.class, () -> socket.bind(new InetSocketAddress(0)));
    assertThat(socket.isBound()).isFalse();
  }

  @Test
  public void bind_null_throwsIllegalArgumentException() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());

    assertThrows(IllegalArgumentException.class, () -> socket.bind(null));
    assertThat(socket.isBound()).isFalse();
  }

  @Test
  public void bind_twice_throwsAlreadyBound() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.bind(address);

    SocketException e =
        assertThrows(SocketException.class, () -> socket.bind(newAbstractAddress()));
    assertThat(e).hasMessageThat().ignoringCase().contains("bound");
    assertThat(socket.getLocalSocketAddress()).isEqualTo(address);
  }

  @Test
  public void bind_afterClose_throwsSocketClosed() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.close();

    SocketException e =
        assertThrows(SocketException.class, () -> socket.bind(newAbstractAddress()));
    assertThat(e).hasMessageThat().ignoringCase().contains("closed");
    assertThat(socket.isBound()).isFalse();
  }

  @Test
  public void bind_listensOnAddress() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket socket = register(new UdsServerSocket());
    assertThat(socket.isBound()).isFalse();
    assertThat(socket.isClosed()).isFalse();
    assertThat(socket.getLocalSocketAddress()).isNull();
    assertThat(socket.toString()).contains("unbound");

    socket.bind(address);

    assertThat(socket.isBound()).isTrue();
    assertThat(socket.getLocalSocketAddress()).isEqualTo(address);
    assertThat(socket.getInetAddress()).isNull();
    assertThat(socket.getLocalPort()).isEqualTo(-1);
    assertThat(socket.toString()).contains(address.toString());
  }

  @Test
  public void bind_addressAlreadyInUse_throwsAndLeavesUnbound() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket first = register(new UdsServerSocket());
    first.bind(address);
    UdsServerSocket second = register(new UdsServerSocket());

    assertThrows(IOException.class, () -> second.bind(address));
    assertThat(second.isBound()).isFalse();
    assertThat(second.getLocalSocketAddress()).isNull();
  }

  @Test
  public void accept_beforeBind_throwsSocketException() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());

    SocketException e = assertThrows(SocketException.class, socket::accept);
    assertThat(e).hasMessageThat().ignoringCase().contains("bound");
  }

  @Test
  public void accept_afterClose_throwsSocketException() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.bind(newAbstractAddress());
    socket.close();

    SocketException e = assertThrows(SocketException.class, socket::accept);
    assertThat(e).hasMessageThat().ignoringCase().contains("closed");
  }

  @Test
  public void accept_returnsConnectedUdsSocket() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket serverSocket = register(new UdsServerSocket());
    serverSocket.bind(address);

    LocalSocket client = register(new LocalSocket());
    client.connect(new LocalSocketAddress(address.getName(), Namespace.ABSTRACT));

    Socket accepted = register(serverSocket.accept());
    assertThat(accepted).isInstanceOf(UdsSocket.Accepted.class);
    assertThat(accepted.getLocalSocketAddress()).isEqualTo(address);

    client.getOutputStream().write(42);
    assertThat(accepted.getInputStream().read()).isEqualTo(42);
  }

  @Test
  public void close_whileAcceptBlocked_wakesAcceptWithoutDeadlock() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.bind(newAbstractAddress());
    CountDownLatch callingAccept = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Socket> acceptFuture =
          executor.submit(
              () -> {
                callingAccept.countDown();
                return socket.accept();
              });
      assertThat(callingAccept.await(5, SECONDS)).isTrue();
      // Give the worker thread a moment to enter the blocking native accept(2) syscall.
      Thread.sleep(50);

      socket.close();

      assertThat(socket.isClosed()).isTrue();
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> acceptFuture.get(5, SECONDS));
      assertThat(e).hasCauseThat().isInstanceOf(IOException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void close_isIdempotentAndReleasesBoundAddress() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.bind(address);

    socket.close();
    socket.close();

    assertThat(socket.isClosed()).isTrue();
    UdsServerSocket rebound = register(new UdsServerSocket());
    rebound.bind(address);
    assertThat(rebound.isBound()).isTrue();
  }

  @Test
  public void close_unbound_marksClosed() throws Exception {
    UdsServerSocket socket = register(new UdsServerSocket());

    socket.close();

    assertThat(socket.isClosed()).isTrue();
    assertThat(socket.isBound()).isFalse();
  }

  @Test
  public void getLocalSocketAddress_afterClose_stillReturnsBoundAddress() throws Exception {
    UdsSocketAddress address = newAbstractAddress();
    UdsServerSocket socket = register(new UdsServerSocket());
    socket.bind(address);

    socket.close();

    assertThat(socket.getLocalSocketAddress()).isEqualTo(address);
    assertThat(socket.isBound()).isTrue();
  }

  @Test
  public void bind_filesystemAddress_acceptsConnectionAndUnlinksOnClose() throws Exception {
    Context appContext = ApplicationProvider.getApplicationContext();
    File socketFile = new File(appContext.getFilesDir(), "uds-socket-test-" + UUID.randomUUID());
    UdsSocketAddress address = UdsSocketAddress.forFile(socketFile);
    UdsServerSocket serverSocket = register(new UdsServerSocket());
    serverSocket.bind(address);

    LocalSocket client = register(new LocalSocket());
    client.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), Namespace.FILESYSTEM));
    Socket accepted = register(serverSocket.accept());
    assertThat(accepted.getLocalSocketAddress()).isEqualTo(address);

    serverSocket.close();
    assertThat(socketFile.exists()).isFalse();
  }

  @Test
  public void abstractListeningSocket_filesystemAddress_throwsIllegalArgumentException() {
    UdsSocketAddress address = UdsSocketAddress.forFile(new File("/data/data/app/files/s.sock"));

    assertThrows(
        IllegalArgumentException.class, () -> UdsAbstractListeningSocket.bindAndListen(address));
  }

  private <T extends Closeable> T register(T closeable) {
    closeables.add(closeable);
    return closeable;
  }

  private static UdsSocketAddress newAbstractAddress() {
    return UdsSocketAddress.forAbstractName("io.grpc.android.uds-socket-test-" + UUID.randomUUID());
  }
}
