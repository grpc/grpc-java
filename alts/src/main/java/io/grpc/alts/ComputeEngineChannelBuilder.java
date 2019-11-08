/*
 * Copyright 2019 The gRPC Authors
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
import javax.net.ssl.SSLException;

/**
 * {@code ManagedChannelBuilder} for Google Compute Engine. This class sets up a secure channel
 * using ALTS if applicable and using TLS as fallback.
 */
public final class ComputeEngineChannelBuilder
    extends ForwardingChannelBuilder<ComputeEngineChannelBuilder> {

  private final NettyChannelBuilder delegate;

  private ComputeEngineChannelBuilder(String target) {
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
    CallCredentials credentials = MoreCallCredentials.from(ComputeEngineCredentials.create());
    Status status = Status.OK;
    if (!CheckGcpEnvironment.isOnGcp()) {
      status =
          Status.INTERNAL.withDescription(
              "Compute Engine Credentials can only be used on Google Cloud Platform");
    }
    delegate().intercept(new CallCredentialsInterceptor(credentials, status));
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static final ComputeEngineChannelBuilder forTarget(String target) {
    return new ComputeEngineChannelBuilder(target);
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static ComputeEngineChannelBuilder forAddress(String name, int port) {
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
