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

import io.grpc.Channel;
import io.grpc.ExperimentalApi;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.InternalNettyServerCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * gRPC secure server builder used for ALTS. This class adds on the necessary ALTS support to create
 * a production server on Google Cloud Platform.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
public final class AltsServerCredentials {
  private static final Logger logger = Logger.getLogger(AltsServerCredentials.class.getName());

  private AltsServerCredentials() {}

  public static ServerCredentials create() {
    return newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
  public static final class Builder {
    private ObjectPool<Channel> handshakerChannelPool =
        SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL);
    private boolean enableUntrustedAlts;

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

    public ServerCredentials build() {
      return InternalNettyServerCredentials.create(buildProtocolNegotiator());
    }

    InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
      if (!CheckGcpEnvironment.isOnGcp()) {
        if (enableUntrustedAlts) {
          logger.log(
              Level.WARNING,
              "Untrusted ALTS mode is enabled and we cannot guarantee the trustworthiness of the "
                  + "ALTS handshaker service");
        } else {
          Status status = Status.INTERNAL.withDescription(
              "ALTS is only allowed to run on Google Cloud Platform");
          return new AltsChannelCredentials.FailingProtocolNegotiator(status);
        }
      }

      return AltsProtocolNegotiator.serverAltsProtocolNegotiator(handshakerChannelPool);
    }
  }
}
