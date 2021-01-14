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

package io.grpc.alts;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.common.collect.ImmutableList;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator.GoogleDefaultProtocolNegotiatorFactory;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalNettyChannelCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import javax.net.ssl.SSLException;

/**
 * Credentials appropriate to contact Google services when running on Google Compute Engine. This
 * class sets up a secure channel using ALTS if applicable and using TLS as fallback. It is a subset
 * of the functionality provided by {@link GoogleDefaultChannelCredentials}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
public final class ComputeEngineChannelCredentials {
  private ComputeEngineChannelCredentials() {}

  /**
   * Creates credentials for Google Compute Engine. This class sets up a secure channel using ALTS
   * if applicable and using TLS as fallback.
   */
  public static ChannelCredentials create() {
    ChannelCredentials nettyCredentials =
        InternalNettyChannelCredentials.create(createClientFactory());
    CallCredentials callCredentials;
    if (CheckGcpEnvironment.isOnGcp()) {
      callCredentials = MoreCallCredentials.from(ComputeEngineCredentials.create());
    } else {
      callCredentials = new FailingCallCredentials(
          Status.INTERNAL.withDescription(
              "Compute Engine Credentials can only be used on Google Cloud Platform"));
    }
    return CompositeChannelCredentials.create(nettyCredentials, callCredentials);
  }

  private static InternalProtocolNegotiator.ClientFactory createClientFactory() {
    SslContext sslContext;
    try {
      sslContext = GrpcSslContexts.forClient().build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
    return new GoogleDefaultProtocolNegotiatorFactory(
        /* targetServiceAccounts= */ ImmutableList.<String>of(),
        SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL),
        sslContext,
        null);
  }
}
