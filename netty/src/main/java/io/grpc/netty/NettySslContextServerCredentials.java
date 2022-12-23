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

package io.grpc.netty;

import com.google.common.base.Preconditions;
import io.grpc.ExperimentalApi;
import io.grpc.ServerCredentials;
import io.netty.handler.ssl.SslContext;

/** A credential that performs TLS with Netty's SslContext as configuration. */
@ExperimentalApi("There is no plan to make this API stable, given transport API instability")
public final class NettySslContextServerCredentials {
  private NettySslContextServerCredentials() {}

  /**
   * Create a credential using Netty's SslContext as configuration. It must have been configured
   * with {@link GrpcSslContexts}, but options could have been overridden.
   */
  public static ServerCredentials create(SslContext sslContext) {
    Preconditions.checkArgument(sslContext.isServer(),
        "Client SSL context can not be used for server");
    GrpcSslContexts.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
    return NettyServerCredentials.create(ProtocolNegotiators.serverTlsFactory(sslContext));
  }
}
