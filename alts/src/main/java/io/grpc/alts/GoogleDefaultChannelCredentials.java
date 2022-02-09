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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator.GoogleDefaultProtocolNegotiatorFactory;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalNettyChannelCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import javax.net.ssl.SSLException;

/**
 * Credentials appropriate to contact Google services. This class sets up a secure channel using
 * ALTS if applicable and uses TLS as fallback.
 */
public final class GoogleDefaultChannelCredentials {
  private GoogleDefaultChannelCredentials() {}

  /**
   * Creates Google default credentials uses a secure channel with ALTS if applicable and uses TLS
   * as fallback.
   */
  public static ChannelCredentials create() {
    return newBuilder().build();
  }

  /**
   * Returns a new instance of {@link Builder}.
   *
   * @since 1.43.0
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builder for {@link GoogleDefaultChannelCredentials} instances.
   *
   * @since 1.43.0
   */
  public static final class Builder {
    private CallCredentials callCredentials;

    private Builder() {}

    /** Constructs GoogleDefaultChannelCredentials with a given call credential. */
    public Builder callCredentials(CallCredentials callCreds) {
      callCredentials = callCreds;
      return this;
    }

    /** Builds a GoogleDefaultChannelCredentials instance. */
    public ChannelCredentials build() {
      ChannelCredentials nettyCredentials =
          InternalNettyChannelCredentials.create(createClientFactory());
      if (callCredentials != null) {
        return CompositeChannelCredentials.create(nettyCredentials, callCredentials);
      }
      CallCredentials callCreds;
      try {
        callCreds = MoreCallCredentials.from(GoogleCredentials.getApplicationDefault());
      } catch (IOException e) {
        callCreds =
            new FailingCallCredentials(
                Status.UNAUTHENTICATED
                    .withDescription("Failed to get Google default credentials")
                    .withCause(e));
      }
      return CompositeChannelCredentials.create(nettyCredentials, callCreds);
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
          sslContext);
    }
  }
}
