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

import io.grpc.ChannelCredentials;
import io.grpc.Internal;

/**
 * Internal {@link NettyChannelCredentials} accessor.  This is intended for usage internal to the
 * gRPC team. If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalNettyChannelCredentials {
  private InternalNettyChannelCredentials() {}

  /** Creates a {@link ChannelCredentials} that will use the provided {@code negotiator}. */
  public static ChannelCredentials create(InternalProtocolNegotiator.ClientFactory negotiator) {
    return NettyChannelCredentials.create(negotiator);
  }

  /**
   * Converts a {@link ChannelCredentials} to a negotiator, in similar fashion as for a new channel.
   *
   * @throws IllegalArgumentException if unable to convert
   */
  public static InternalProtocolNegotiator.ClientFactory toNegotiator(
      ChannelCredentials channelCredentials) {
    final ProtocolNegotiators.FromChannelCredentialsResult result =
        ProtocolNegotiators.from(channelCredentials);
    if (result.error != null) {
      throw new IllegalArgumentException(result.error);
    }
    final class ClientFactory implements InternalProtocolNegotiator.ClientFactory {

      @Override
      public InternalProtocolNegotiator.ProtocolNegotiator newNegotiator() {
        return new InternalProtocolNegotiator.ProtocolNegotiatorAdapter(
            result.negotiator.newNegotiator());
      }

      @Override
      public int getDefaultPort() {
        return result.negotiator.getDefaultPort();
      }
    }

    return new ClientFactory();
  }
}
