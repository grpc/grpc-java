/*
 * Copyright 2018 The gRPC Authors
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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.grpc.CallCredentials;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator.GoogleDefaultProtocolNegotiatorFactory;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * Google default version of {@code ManagedChannelBuilder}. This class sets up a secure channel
 * using ALTS if applicable and using TLS as fallback.
 */
public final class GoogleDefaultChannelBuilder
    extends ForwardingChannelBuilder<GoogleDefaultChannelBuilder> {

  private final NettyChannelBuilder delegate;

  private GoogleDefaultChannelBuilder(String target) {
    delegate = NettyChannelBuilder.forTarget(target);
    SslContext sslContext;
    try {
      sslContext = GrpcSslContexts.forClient().build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(
        delegate(),
        new GoogleDefaultProtocolNegotiatorFactory(
            /* targetServiceAccounts= */ ImmutableList.<String>of(),
            SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL),
            sslContext));
    @Nullable CallCredentials credentials = null;
    Status status = Status.OK;
    try {
      credentials = MoreCallCredentials.from(GoogleCredentials.getApplicationDefault());
    } catch (IOException e) {
      status =
          Status.UNAUTHENTICATED
              .withDescription("Failed to get Google default credentials")
              .withCause(e);
    }
    delegate().intercept(new CallCredentialsInterceptor(credentials, status));
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static final GoogleDefaultChannelBuilder forTarget(String target) {
    return new GoogleDefaultChannelBuilder(target);
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static GoogleDefaultChannelBuilder forAddress(String name, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port));
  }

  @Override
  protected NettyChannelBuilder delegate() {
    return delegate;
  }

  @VisibleForTesting
  ProtocolNegotiator getProtocolNegotiatorForTest() {
    SslContext sslContext;
    try {
      sslContext = GrpcSslContexts.forClient().build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
    return new GoogleDefaultProtocolNegotiatorFactory(
        /* targetServiceAccounts= */ ImmutableList.<String>of(),
        SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL),
        sslContext)
            .buildProtocolNegotiator();
  }
}
