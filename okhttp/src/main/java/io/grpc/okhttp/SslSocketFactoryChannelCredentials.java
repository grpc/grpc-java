/*
 * Copyright 2020 The gRPC Authors
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
@ExperimentalApi("There is no plan to make this API stable, given transport API instability")
public final class SslSocketFactoryChannelCredentials {
  private SslSocketFactoryChannelCredentials() {}

  public static io.grpc.ChannelCredentials create(SSLSocketFactory factory) {
    return new ChannelCredentials(factory);
  }

  public static io.grpc.ChannelCredentials create(
      SSLSocketFactory factory, com.squareup.okhttp.ConnectionSpec connectionSpec) {
    return new ChannelCredentials(factory, Utils.convertSpec(connectionSpec));
  }

  public static io.grpc.ChannelCredentials create(
      SSLSocketFactory factory, String[] tlsVersions, String[] cipherSuiteList, boolean supportsTlsExtensions) {
    ConnectionSpec connectionSpec = new ConnectionSpec.Builder(true)
        .tlsVersions(tlsVersions)
        .cipherSuites(cipherSuiteList)
        .supportsTlsExtensions(supportsTlsExtensions)
        .build();
    return new ChannelCredentials(factory, connectionSpec);
  }

  // Hide implementation detail of how these credentials operate
  static final class ChannelCredentials extends io.grpc.ChannelCredentials {
    private final SSLSocketFactory factory;
    private final ConnectionSpec connectionSpec;

    ChannelCredentials(SSLSocketFactory factory) {
      this(factory, OkHttpChannelBuilder.INTERNAL_DEFAULT_CONNECTION_SPEC);
    }

    ChannelCredentials(SSLSocketFactory factory, ConnectionSpec connectionSpec) {
      this.factory = Preconditions.checkNotNull(factory, "factory");
      this.connectionSpec = Preconditions.checkNotNull(connectionSpec, "connectionSpec");
    }

    public SSLSocketFactory getFactory() {
      return factory;
    }

    public ConnectionSpec getConnectionSpec() {
      return connectionSpec;
    }

    @Override
    public io.grpc.ChannelCredentials withoutBearerTokens() {
      return this;
    }
  }
}
