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

import com.google.common.collect.ImmutableList;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator.ClientAltsProtocolNegotiatorFactory;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AsciiString;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides secure and authenticated commmunication between two cloud VMs using ALTS.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4151")
public final class AltsChannelCredentials {
  private static final Logger logger = Logger.getLogger(AltsChannelCredentials.class.getName());

  private AltsChannelCredentials() {}

  public static ChannelCredentials create() {
    return newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4151")
  public static final class Builder {
    private final ImmutableList.Builder<String> targetServiceAccountsBuilder =
        ImmutableList.builder();
    private ObjectPool<Channel> handshakerChannelPool =
        SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL);
    private boolean enableUntrustedAlts;

    /**
     * Adds an expected target service accounts. One of the added service accounts should match peer
     * service account in the handshaker result. Otherwise, the handshake fails.
     */
    public Builder addTargetServiceAccount(String targetServiceAccount) {
      targetServiceAccountsBuilder.add(targetServiceAccount);
      return this;
    }

    /**
     * Enables untrusted ALTS for testing. If this function is called, we will not check whether
     * ALTS is running on Google Cloud Platform.
     */
    public Builder enableUntrustedAltsForTesting() {
      enableUntrustedAlts = true;
      return this;
    }

    /** Sets a new handshaker service address for testing. */
    public Builder setHandshakerAddressForTesting(String handshakerAddress) {
      // Instead of using the default shared channel to the handshaker service, create a separate
      // resource to the test address.
      handshakerChannelPool =
          SharedResourcePool.forResource(
              HandshakerServiceChannel.getHandshakerChannelForTesting(handshakerAddress));
      return this;
    }

    public ChannelCredentials build() {
      return InternalNettyChannelCredentials.create(buildProtocolNegotiatorFactory());
    }

    InternalProtocolNegotiator.ClientFactory buildProtocolNegotiatorFactory() {
      if (!CheckGcpEnvironment.isOnGcp()) {
        if (enableUntrustedAlts) {
          logger.log(
              Level.WARNING,
              "Untrusted ALTS mode is enabled and we cannot guarantee the trustworthiness of the "
                  + "ALTS handshaker service");
        } else {
          Status status = Status.INTERNAL.withDescription(
              "ALTS is only allowed to run on Google Cloud Platform");
          return new FailingProtocolNegotiatorFactory(status);
        }
      }

      return new ClientAltsProtocolNegotiatorFactory(
          targetServiceAccountsBuilder.build(), handshakerChannelPool);
    }
  }

  private static final class FailingProtocolNegotiatorFactory
      implements InternalProtocolNegotiator.ClientFactory {
    private final Status status;

    public FailingProtocolNegotiatorFactory(Status status) {
      this.status = status;
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return new FailingProtocolNegotiator(status);
    }

    @Override
    public int getDefaultPort() {
      return 443;
    }
  }

  private static final AsciiString SCHEME = AsciiString.of("https");

  static final class FailingProtocolNegotiator implements ProtocolNegotiator {
    private final Status status;

    public FailingProtocolNegotiator(Status status) {
      this.status = status;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      return new ChannelHandlerAdapter() {
        @Override public void handlerAdded(ChannelHandlerContext ctx) {
          ctx.fireExceptionCaught(status.asRuntimeException());
        }
      };
    }

    @Override
    public void close() {}
  }
}
