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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.io.Files;
import io.grpc.CallOptions;
import io.grpc.Grpc;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ServerSocketsList;
import io.grpc.InternalChannelz.ServerStats;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.InternalWithLogId;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.StringMarshaller;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device tests for {@link UdsServerBuilder} and {@link UdsChannelBuilder}, covering Android
 * {@code AF_UNIX} kernel semantics and package-private {@code io.grpc.android} plumbing not
 * exercised by Robolectric unit tests or {@code UdsChannelInteropTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class UdsEndToEndTest {
  private static final MethodDescriptor<String, String> ECHO_METHOD =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Echo", "Echo"))
          .setRequestMarshaller(StringMarshaller.INSTANCE)
          .setResponseMarshaller(StringMarshaller.INSTANCE)
          .build();

  private static final ServerServiceDefinition ECHO_SERVICE = echoService("echo");

  private static ServerServiceDefinition echoService(String prefix) {
    return ServerServiceDefinition.builder("test.Echo")
        .addMethod(
            ECHO_METHOD,
            ServerCalls.asyncUnaryCall(
                (String request, StreamObserver<String> responseObserver) -> {
                  responseObserver.onNext(prefix + ": " + request);
                  responseObserver.onCompleted();
                }))
        .build();
  }

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final Context appContext = ApplicationProvider.getApplicationContext();

  @Test
  public void unaryRpc_filesystemSocket_echoesRequest() throws Exception {
    File socketFile = newSocketFile();
    Server server = startFileServer(socketFile);
    ManagedChannel channel = newFileChannel(socketFile);

    assertThat(server.getListenSockets()).containsExactly(UdsSocketAddress.forFile(socketFile));
    assertThat(echo(channel, "hello")).isEqualTo("echo: hello");
  }

  @Test
  public void getListenSockets_returnsAbstractListenAddress() throws Exception {
    String name = newAbstractName();
    Server server = startAbstractServer(name);

    assertThat(server.getListenSockets()).containsExactly(UdsSocketAddress.forAbstractName(name));
  }

  @Test
  public void filesystemSocket_isOwnedByThisUidWithMode0600InPrivateDir() throws Exception {
    // Single-uid proxy for kernel access control: connect() requires write on the socket (0600) and
    // search on every ancestor, and replacing the socket requires write on files/ (not
    // world-writable). files/ itself is 0771 on API 24/26/33, so the 0700 app data dir is what
    // blocks traversal by other uids.
    File socketFile = newSocketFile();
    startFileServer(socketFile);

    StructStat socketStat = Os.stat(socketFile.getAbsolutePath());
    assertThat(OsConstants.S_ISSOCK(socketStat.st_mode)).isTrue();
    assertThat(Integer.toOctalString(socketStat.st_mode & 0777)).isEqualTo("600");
    assertThat(socketStat.st_uid).isEqualTo(Process.myUid());
    StructStat filesDirStat = Os.stat(socketFile.getParent());
    assertThat(filesDirStat.st_uid).isEqualTo(Process.myUid());
    assertThat(filesDirStat.st_mode & OsConstants.S_IWOTH).isEqualTo(0);

    // Platform-assumption canary: installd creates the app data dir with mode 0700 only when
    // targetSdkVersion >= 24 (0751 for older targets).
    StructStat dataDirStat = Os.stat(appContext.getDataDir().getPath());
    assertThat(dataDirStat.st_uid).isEqualTo(Process.myUid());
    assertThat(Integer.toOctalString(dataDirStat.st_mode & 0777)).isEqualTo("700");
  }

  @Test
  public void localAddressAttribute_isTheListenAddress() throws Exception {
    String name = newAbstractName();
    AtomicReference<SocketAddress> localAddr = new AtomicReference<>();
    Server server =
        newAbstractServerBuilder(name)
            .addService(ECHO_SERVICE)
            .intercept(
                capturingInterceptor(
                    call ->
                        localAddr.set(call.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR))))
            .build();
    grpcCleanup.register(server.start());

    assertThat(echo(newAbstractChannel(name), "hi")).isEqualTo("echo: hi");
    assertThat(localAddr.get()).isEqualTo(UdsSocketAddress.forAbstractName(name));
  }

  @Test
  public void concurrentConnections_servedIndependently() throws Exception {
    String name = newAbstractName();
    Server server = startAbstractServer(name);
    ManagedChannel channel1 = newAbstractChannel(name);
    ManagedChannel channel2 = newAbstractChannel(name);

    assertThat(echo(channel1, "a1")).isEqualTo("echo: a1");
    assertThat(echo(channel2, "b1")).isEqualTo("echo: b1");
    assertThat(echo(channel1, "a2")).isEqualTo("echo: a2");
    assertThat(echo(channel2, "b2")).isEqualTo("echo: b2");

    ServerSocketsList serverSockets =
        InternalChannelz.instance()
            .getServerSockets(
                InternalChannelz.id((InternalWithLogId) server),
                /* fromId= */ 0,
                /* maxPageSize= */ 2);
    assertThat(serverSockets.sockets).hasSize(2);
    assertThat(serverSockets.end).isTrue();
  }

  @Test
  public void shutdown_unblocksAcceptAndUnlinksFilesystemSocket() throws Exception {
    File socketFile = newSocketFile();
    Server server = startFileServer(socketFile);
    assertThat(echo(newFileChannel(socketFile), "hi")).isEqualTo("echo: hi");

    server.shutdown();
    assertThat(server.awaitTermination(10, SECONDS)).isTrue();
    assertThat(socketFile.exists()).isFalse();
  }

  @Test
  public void shutdown_unblocksAccept_abstractName() throws Exception {
    String name = newAbstractName();
    Server server = startAbstractServer(name);
    // After serving an RPC the accept loop has gone back to block in accept() with nothing pending,
    // so termination proves that closing the listening socket from another thread wakes it up.
    assertThat(echo(newAbstractChannel(name), "hi")).isEqualTo("echo: hi");

    server.shutdown();
    assertThat(server.awaitTermination(10, SECONDS)).isTrue();
  }

  @Test
  public void shutdownNow_withConnectedClient_terminatesAndClientSeesUnavailable()
      throws Exception {
    String name = newAbstractName();
    Server server = startAbstractServer(name);
    ManagedChannel channel = newAbstractChannel(name);
    assertThat(echo(channel, "hi")).isEqualTo("echo: hi");

    server.shutdownNow();
    assertThat(server.awaitTermination(10, SECONDS)).isTrue();

    StatusRuntimeException e =
        assertThrows(StatusRuntimeException.class, () -> echo(channel, "anyone?"));
    assertThat(e.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
  }

  @Test
  public void shutdown_releasesAbstractName_andAllowsRebind() throws Exception {
    String name = newAbstractName();
    Server server1 = startAbstractServer(name);
    assertThat(echo(newAbstractChannel(name), "one")).isEqualTo("echo: one");
    server1.shutdown();
    assertThat(server1.awaitTermination(10, SECONDS)).isTrue();

    startAbstractServer(name);
    assertThat(echo(newAbstractChannel(name), "two")).isEqualTo("echo: two");
  }

  @Test
  public void start_abstractNameAlreadyBound_fails() throws Exception {
    String name = newAbstractName();
    startAbstractServer(name);

    Server duplicate = grpcCleanup.register(newAbstractServerBuilder(name).build());
    assertThrows(IOException.class, duplicate::start);
  }

  @Test
  public void start_staleSocketFileAtSocketPath_isReplaced() throws Exception {
    // Simulates the socket file left behind when a previous process was killed without cleanup:
    // bind a LocalSocket at the path and close it, which does not unlink the file.
    File staleFile = newSocketFile();
    LocalSocket stale = new LocalSocket();
    stale.bind(new LocalSocketAddress(staleFile.getAbsolutePath(), Namespace.FILESYSTEM));
    stale.close();
    assertThat(OsConstants.S_ISSOCK(Os.stat(staleFile.getAbsolutePath()).st_mode)).isTrue();

    startFileServer(staleFile);
    assertThat(echo(newFileChannel(staleFile), "hi")).isEqualTo("echo: hi");
  }

  @Test
  public void start_regularFileAtSocketPath_failsAndLeavesFileIntact() throws Exception {
    File dataFile = newSocketFile();
    Files.asCharSink(dataFile, UTF_8).write("precious");

    Server server = grpcCleanup.register(newFileServerBuilder(dataFile).build());
    IOException e = assertThrows(IOException.class, server::start);

    assertThat(e).hasMessageThat().contains("non-socket");
    assertThat(Files.asCharSource(dataFile, UTF_8).read()).isEqualTo("precious");
  }

  @Test
  public void start_symlinkToSocketAtSocketPath_failsAndLeavesSymlinkIntact() throws Exception {
    // Point the symlink at a live socket so that stat() (following symlinks) would see S_IFSOCK
    // and let bind() unlink the symlink, whereas lstat() sees S_IFLNK and refuses.
    File targetSocket = newSocketFile();
    startFileServer(targetSocket);
    File symlink = newSocketFile();
    Os.symlink(targetSocket.getAbsolutePath(), symlink.getAbsolutePath());

    Server server = grpcCleanup.register(newFileServerBuilder(symlink).build());
    IOException e = assertThrows(IOException.class, server::start);

    assertThat(e).hasMessageThat().contains("non-socket");
    assertThat(OsConstants.S_ISLNK(Os.lstat(symlink.getAbsolutePath()).st_mode)).isTrue();
    assertThat(echo(newFileChannel(targetSocket), "hi")).isEqualTo("echo: hi");
  }

  @Test
  public void start_socketPathAlreadyBound_newServerTakesOverPath() throws Exception {
    File socketFile = newSocketFile();
    Server first = startFileServer(socketFile, echoService("first"));
    ManagedChannel connectedToFirst = newFileChannel(socketFile);
    assertThat(echo(connectedToFirst, "hi")).isEqualTo("first: hi");

    // Android's LocalSocket.bind() replaces the existing socket file rather than failing.
    startFileServer(socketFile, echoService("second"));

    assertThat(echo(connectedToFirst, "hi")).isEqualTo("first: hi");
    assertThat(echo(newFileChannel(socketFile), "hi")).isEqualTo("second: hi");

    first.shutdown();
    assertThat(first.awaitTermination(10, SECONDS)).isTrue();
    assertThat(socketFile.exists()).isTrue();
    assertThat(echo(newFileChannel(socketFile), "again")).isEqualTo("second: again");
  }

  @Test
  public void channelz_reportsSocketStatsWithoutErrors() throws Exception {
    InternalChannelz channelz = InternalChannelz.instance();
    List<InternalInstrumented<ChannelStats>> existingChannels =
        channelz.getRootChannels(/* fromId= */ 0, Integer.MAX_VALUE).channels;
    long nextChannelId =
        existingChannels.isEmpty()
            ? 0
            : InternalChannelz.id(existingChannels.get(existingChannels.size() - 1)) + 1;

    String name = newAbstractName();
    Server server = startAbstractServer(name);
    ManagedChannel channel = newAbstractChannel(name);
    assertThat(echo(channel, "stats")).isEqualTo("echo: stats");

    List<InternalInstrumented<ChannelStats>> newChannels =
        channelz.getRootChannels(nextChannelId, Integer.MAX_VALUE).channels;
    assertThat(newChannels).hasSize(1);
    ChannelStats channelStats = newChannels.get(0).getStats().get(10, SECONDS);
    assertThat(channelStats.subchannels).hasSize(1);
    ChannelStats subchannelStats =
        channelz
            .getSubchannel(InternalChannelz.id(channelStats.subchannels.get(0)))
            .getStats()
            .get(10, SECONDS);
    assertThat(subchannelStats.sockets).hasSize(1);
    SocketStats clientSocketStats =
        channelz
            .getSocket(InternalChannelz.id(subchannelStats.sockets.get(0)))
            .getStats()
            .get(10, SECONDS);
    assertThat(clientSocketStats.socketOptions.others).isNotEmpty();
    assertThat(clientSocketStats.socketOptions.others.values())
        .doesNotContain("channelz_internal_error");

    UdsSocketAddress listenAddr = UdsSocketAddress.forAbstractName(name);
    @SuppressWarnings("unchecked")
    InternalInstrumented<ServerStats> instrumentedServer =
        (InternalInstrumented<ServerStats>) server;
    ServerStats serverStats = instrumentedServer.getStats().get(10, SECONDS);
    assertThat(serverStats.listenSockets).hasSize(1);
    SocketStats listenSocketStats = serverStats.listenSockets.get(0).getStats().get(10, SECONDS);
    assertThat(listenSocketStats.local).isEqualTo(listenAddr);

    ServerSocketsList serverSockets =
        channelz.getServerSockets(
            InternalChannelz.id(instrumentedServer), /* fromId= */ 0, /* maxPageSize= */ 1);
    assertThat(serverSockets.sockets).hasSize(1);
    assertThat(serverSockets.end).isTrue();
    SocketStats acceptedSocketStats =
        channelz
            .getSocket(InternalChannelz.id(serverSockets.sockets.get(0)))
            .getStats()
            .get(10, SECONDS);
    assertThat(acceptedSocketStats.local).isEqualTo(listenAddr);
    assertThat(acceptedSocketStats.socketOptions.others).isNotEmpty();
    assertThat(acceptedSocketStats.socketOptions.others.values())
        .doesNotContain("channelz_internal_error");
  }

  /** A pass-through interceptor that hands every call to {@code observer} first. */
  private static ServerInterceptor capturingInterceptor(Consumer<ServerCall<?, ?>> observer) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        observer.accept(call);
        return next.startCall(call, headers);
      }
    };
  }

  private static ServerBuilder<?> newFileServerBuilder(File socketFile) {
    return UdsServerBuilder.forPath(socketFile.getAbsolutePath(), Namespace.FILESYSTEM);
  }

  private static ServerBuilder<?> newAbstractServerBuilder(String name) {
    return UdsServerBuilder.forPath(name, Namespace.ABSTRACT);
  }

  private Server startFileServer(File socketFile) throws IOException {
    return startFileServer(socketFile, ECHO_SERVICE);
  }

  private Server startFileServer(File socketFile, ServerServiceDefinition service)
      throws IOException {
    Server server = newFileServerBuilder(socketFile).addService(service).build();
    return grpcCleanup.register(server.start());
  }

  private Server startAbstractServer(String name) throws IOException {
    Server server = newAbstractServerBuilder(name).addService(ECHO_SERVICE).build();
    return grpcCleanup.register(server.start());
  }

  private ManagedChannel newFileChannel(File socketFile) {
    return grpcCleanup.register(
        UdsChannelBuilder.forPath(socketFile.getAbsolutePath(), Namespace.FILESYSTEM).build());
  }

  private ManagedChannel newAbstractChannel(String name) {
    return grpcCleanup.register(UdsChannelBuilder.forPath(name, Namespace.ABSTRACT).build());
  }

  private static String echo(ManagedChannel channel, String request) {
    return ClientCalls.blockingUnaryCall(
        channel, ECHO_METHOD, CallOptions.DEFAULT.withDeadlineAfter(10, SECONDS), request);
  }

  private File newSocketFile() {
    return new File(appContext.getFilesDir(), "uds-test-" + UUID.randomUUID());
  }

  private static String newAbstractName() {
    return "io.grpc.android.uds-test-" + UUID.randomUUID();
  }
}
