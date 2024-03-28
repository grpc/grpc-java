/*
 * Copyright 2021 The gRPC Authors
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

import android.net.LocalSocketAddress.Namespace;
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import java.lang.reflect.InvocationTargetException;
import javax.annotation.Nullable;
import javax.net.SocketFactory;

/**
 * Creates a UDS channel by passing in a specialized SocketFactory into an OkHttpChannelBuilder. The
 * UdsSockets produced by this factory communicate over Android's LocalSockets.
 *
 * <p>Example Usage <code>
 *   Channel channel = UdsChannelBuilder.forPath("/data/data/my.app/app.socket",
 *     Namespace.FILESYSTEM).build();
 *   stub = MyService.newStub(channel);
 * </code>
 *
 * <p>This class uses a safe-for-production hack to workaround NameResolver's inability to safely
 * return non-IP SocketAddress types. The hack simply ignores the name resolver results and connects
 * to the UDS name provided during construction instead. This class is expected to be replaced with
 * a `unix:` name resolver when possible.
 */
@ExperimentalApi("A stopgap. Not intended to be stabilized")
public final class UdsChannelBuilder {
  @Nullable
  @SuppressWarnings("rawtypes")
  private static final Class<? extends ManagedChannelBuilder> OKHTTP_CHANNEL_BUILDER_CLASS =
      findOkHttp();

  @SuppressWarnings("rawtypes")
  private static Class<? extends ManagedChannelBuilder> findOkHttp() {
    try {
      return Class.forName("io.grpc.okhttp.OkHttpChannelBuilder")
          .asSubclass(ManagedChannelBuilder.class);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Returns a channel to the UDS endpoint specified by the file-path.
   *
   * @param path unix file system path to use for Unix Domain Socket.
   * @param namespace the type of the namespace that the path belongs to.
   */
  public static ManagedChannelBuilder<?> forPath(String path, Namespace namespace) {
    if (OKHTTP_CHANNEL_BUILDER_CLASS == null) {
      throw new UnsupportedOperationException("OkHttpChannelBuilder not found on the classpath");
    }
    try {
      // Target 'dns:///localhost' is unused, but necessary as an argument for OkHttpChannelBuilder.
      // TLS is unsupported because Conscrypt assumes the platform Socket implementation to improve
      // performance by using the file descriptor directly.
      Object o = OKHTTP_CHANNEL_BUILDER_CLASS
          .getMethod("forTarget", String.class, ChannelCredentials.class)
          .invoke(null, "dns:///localhost", InsecureChannelCredentials.create());
      ManagedChannelBuilder<?> builder = OKHTTP_CHANNEL_BUILDER_CLASS.cast(o);
      OKHTTP_CHANNEL_BUILDER_CLASS
          .getMethod("socketFactory", SocketFactory.class)
          .invoke(builder, new UdsSocketFactory(path, namespace));
      return builder;
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Failed to create OkHttpChannelBuilder", e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Failed to create OkHttpChannelBuilder", e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("Failed to create OkHttpChannelBuilder", e);
    }
  }

  private UdsChannelBuilder() {}
}
