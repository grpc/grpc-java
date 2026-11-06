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

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.LocalSocketAddress.Namespace;
import io.grpc.ExperimentalApi;
import io.grpc.InsecureServerCredentials;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketAddress;
import javax.annotation.Nullable;
import javax.net.ServerSocketFactory;

/**
 * Builds a gRPC server that listens on an Android Unix domain socket, the counterpart of {@link
 * UdsChannelBuilder}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Server server = UdsServerBuilder.forPath(
 *         new File(context.getFilesDir(), "app.socket").getPath(), Namespace.FILESYSTEM)
 *     .addService(new MyServiceImpl())
 *     .build()
 *     .start();
 * }</pre>
 *
 * <p>Currently requires {@code grpc-okhttp} at runtime. Only plaintext is supported ({@link
 * io.grpc.ServerCall#getSecurityLevel()} is {@link io.grpc.SecurityLevel#NONE}). Once started,
 * {@link io.grpc.Server#getPort()} returns {@code -1}, and {@link
 * io.grpc.Server#getListenSockets()} and {@link io.grpc.Grpc#TRANSPORT_ATTR_LOCAL_ADDR} report the
 * bound {@link SocketAddress}.
 *
 * <h2>Security</h2>
 *
 * <p>Like any gRPC server, a UDS server does not itself decide who may call it. The address it
 * listens on decides who can <em>reach</em> it, and application code (interceptors and services)
 * decides what each caller may <em>do</em>. The choice of {@link Namespace} is therefore the main
 * security decision.
 *
 * <h3>Filesystem sockets</h3>
 *
 * <p>With {@link Namespace#FILESYSTEM} access control is enforced by the kernel: a process can
 * connect only if it has write permission on the socket file and search permission on every
 * ancestor directory, and SELinux permits it. The server creates the socket file with mode {@code
 * 0600}, so a socket under the app's private storage ({@code Context.getFilesDir()}) can only be
 * reached by this app's own uid and by {@code root}. Prefer {@link Namespace#FILESYSTEM} under
 * {@code Context.getFilesDir()} unless cross-process reachability without a shared directory is
 * required.
 *
 * <p>That mode says nothing about who can <em>replace</em> the file: in a directory other uids can
 * write to (such as {@code /data/local/tmp}), they can unlink it and bind their own socket at the
 * path, so keep the socket under the app's private data directory.
 *
 * <p>The same permissions apply to {@code adb forward tcp:PORT localfilesystem:PATH}, whose
 * on-device end is {@code adbd}. That works after {@code adb root} (userdebug and eng builds, and
 * most emulator images), where {@code adbd} is uid 0; on a user build {@code adbd} runs as uid 2000
 * ({@code shell}) and cannot reach an app-private socket file at all.
 *
 * <h3>Abstract-namespace sockets</h3>
 *
 * <p>With {@link Namespace#ABSTRACT} the server listens on a name in Linux's abstract socket
 * namespace, which has no file permissions; who can reach it is decided by SELinux, which only
 * lets a process {@code connectto} a Unix stream socket whose owner has the same SELinux label as
 * itself (or is a trusted domain):
 *
 * <ul>
 *   <li><b>Android 9+, and the app hosting the server targets API 28+:</b> <b>no other app can
 *       connect</b> (unless it shares this app's uid), whatever that app targets, because each such
 *       app has its own label. The server is reachable by this app, by {@code adbd} on any build,
 *       and by privileged system processes.
 *   <li><b>Android 9+, and the app hosting the server targets API 27 or lower:</b> <b>other apps of
 *       the same Android user targeting API 27 or lower can connect</b>, because they share a
 *       per-user label; apps targeting API 28+ still cannot.
 *   <li><b>Android 8.1 or earlier:</b> <b>other apps running as the same Android user can
 *       connect</b>, because there are no per-app labels.
 * </ul>
 *
 * <p>SELinux lets {@code adbd} connect to app sockets on every build, rooted or not. Once USB (or
 * wireless) debugging is on, anyone holding the device and a previously-authorized {@code adb}
 * host, or who can get the user to accept a new host's key, can run {@code adb forward tcp:PORT
 * localabstract:NAME} and call every method the server exposes (and for a debuggable app,
 * {@code adb shell run-as} can connect as the app itself). The server does not yet expose peer
 * identity, so serve nothing that anyone with debugging access (or, on the older tiers, another app
 * of the same user) may not see.
 *
 * <p>Do not rely on a secret abstract name to keep {@code adbd} (or, before Android 10, other apps)
 * out: {@code adb shell} can list bound abstract names from {@code /proc/net/unix} (as could any
 * app before Android 10), and a hardcoded name is visible in the APK.
 *
 * <p>{@link Namespace#RESERVED} is not supported.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A socket file is created by {@code Server.start()} and unlinked when the server shuts down.
 * A stale socket file left behind by a killed process is replaced, so it won't prevent startup,
 * but a second server started on the same path silently takes over new connections rather than
 * failing. Anything else at the path (a regular file, a directory, a symlink) makes {@code
 * Server.start()} fail with an {@link java.io.IOException} and is left untouched, so a mistyped
 * path cannot destroy data.
 *
 * <p>{@code getFilesDir()} is in credential-encrypted storage, which is unavailable until the user
 * has unlocked the device after a reboot; a server that must run during Direct Boot should use a
 * path under {@code Context.createDeviceProtectedStorageContext().getFilesDir()} instead.
 *
 * <p>An abstract name needs no cleanup, and {@code Server.start()} fails if the name is already in
 * use.
 */
@ExperimentalApi("A stopgap. Not intended to be stabilized")
public final class UdsServerBuilder {
  @Nullable
  @SuppressWarnings("rawtypes")
  private static final Class<? extends ServerBuilder> OKHTTP_SERVER_BUILDER_CLASS = findOkHttp();

  @SuppressWarnings("rawtypes")
  private static Class<? extends ServerBuilder> findOkHttp() {
    try {
      return Class.forName("io.grpc.okhttp.OkHttpServerBuilder").asSubclass(ServerBuilder.class);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Returns a server builder that will listen on the UDS endpoint specified by {@code path}.
   *
   * <p>Linux limits {@code sun_path} to 107 UTF-8 bytes; longer paths or names make {@code
   * Server.start()} fail with {@link java.io.IOException}.
   *
   * @param path filesystem path (for {@link Namespace#FILESYSTEM}, resolved with {@link
   *     File#getAbsolutePath()}) or abstract socket name without the leading NUL byte (for {@link
   *     Namespace#ABSTRACT})
   * @param namespace the namespace {@code path} belongs to
   * @throws IllegalArgumentException if {@code namespace} is {@link Namespace#RESERVED}
   * @throws UnsupportedOperationException if {@code grpc-okhttp} is not available at runtime
   */
  public static ServerBuilder<?> forPath(String path, Namespace namespace) {
    checkNotNull(path, "path");
    checkNotNull(namespace, "namespace");
    UdsSocketAddress address;
    switch (namespace) {
      case FILESYSTEM:
        address = UdsSocketAddress.forFile(new File(path));
        break;
      case ABSTRACT:
        address = UdsSocketAddress.forAbstractName(path);
        break;
      default:
        throw new IllegalArgumentException("Unsupported namespace " + namespace);
    }
    return forAddress(address);
  }

  private static ServerBuilder<?> forAddress(UdsSocketAddress address) {
    if (OKHTTP_SERVER_BUILDER_CLASS == null) {
      throw new UnsupportedOperationException("OkHttpServerBuilder not found on the classpath");
    }
    try {
      // TLS is unsupported because Conscrypt assumes the platform Socket implementation to improve
      // performance by using the file descriptor directly.
      Object o = OKHTTP_SERVER_BUILDER_CLASS
          .getMethod("forPort", SocketAddress.class, ServerCredentials.class)
          .invoke(null, address, InsecureServerCredentials.create());
      ServerBuilder<?> builder = OKHTTP_SERVER_BUILDER_CLASS.cast(o);
      OKHTTP_SERVER_BUILDER_CLASS
          .getMethod("socketFactory", ServerSocketFactory.class)
          .invoke(builder, UdsServerSocketFactory.create());
      return builder;
    } catch (IllegalAccessException | NoSuchMethodException e) {
      throw new UnsupportedOperationException("Failed to create OkHttpServerBuilder", e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new AssertionError(e);
    }
  }

  private UdsServerBuilder() {}
}
