/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.okhttp;

import com.google.common.base.Preconditions;
import io.grpc.ExperimentalApi;
import io.grpc.okhttp.internal.ConnectionSpec;
import javax.net.ssl.SSLSocketFactory;

/** A credential with full control over the SSLSocketFactory. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1785")
public final class SslSocketFactoryServerCredentials {
  private SslSocketFactoryServerCredentials() {}

  public static io.grpc.ServerCredentials create(SSLSocketFactory factory) {
    return new ServerCredentials(factory);
  }

  public static io.grpc.ServerCredentials create(
      SSLSocketFactory factory, com.squareup.okhttp.ConnectionSpec connectionSpec) {
    return new ServerCredentials(factory, Utils.convertSpec(connectionSpec));
  }

  // Hide implementation detail of how these credentials operate
  static final class ServerCredentials extends io.grpc.ServerCredentials {
    private final SSLSocketFactory factory;
    private final ConnectionSpec connectionSpec;

    ServerCredentials(SSLSocketFactory factory) {
      this(factory, OkHttpChannelBuilder.INTERNAL_DEFAULT_CONNECTION_SPEC);
    }

    ServerCredentials(SSLSocketFactory factory, ConnectionSpec connectionSpec) {
      this.factory = Preconditions.checkNotNull(factory, "factory");
      this.connectionSpec = Preconditions.checkNotNull(connectionSpec, "connectionSpec");
    }

    public SSLSocketFactory getFactory() {
      return factory;
    }

    public ConnectionSpec getConnectionSpec() {
      return connectionSpec;
    }
  }
}
