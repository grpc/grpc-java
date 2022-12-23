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

import io.grpc.Internal;
import io.grpc.ServerCredentials;
import io.grpc.internal.ObjectPool;
import java.util.concurrent.Executor;

/**
 * Internal {@link NettyServerCredentials} accessor.  This is intended for usage internal to the
 * gRPC team. If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalNettyServerCredentials {
  private InternalNettyServerCredentials() {}

  /** Creates a {@link ServerCredentials} that will use the provided {@code negotiator}. */
  public static ServerCredentials create(InternalProtocolNegotiator.ProtocolNegotiator negotiator) {
    return NettyServerCredentials.create(ProtocolNegotiators.fixedServerFactory(negotiator));
  }

  /**
   * Creates a {@link ServerCredentials} that will use the provided {@code negotiator}. Use of
   * {@link #create(io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator)} is preferred over
   * this method when possible.
   */
  public static ServerCredentials create(InternalProtocolNegotiator.ServerFactory negotiator) {
    return NettyServerCredentials.create(negotiator);
  }

  /**
   * Converts a {@link ServerCredentials} to a negotiator, in similar fashion as for a new server.
   *
   * @throws IllegalArgumentException if unable to convert
   */
  public static InternalProtocolNegotiator.ServerFactory toNegotiator(
      ServerCredentials serverCredentials) {
    final ProtocolNegotiators.FromServerCredentialsResult result =
        ProtocolNegotiators.from(serverCredentials);
    if (result.error != null) {
      throw new IllegalArgumentException(result.error);
    }
    final class ServerFactory implements InternalProtocolNegotiator.ServerFactory {
      @Override
      public InternalProtocolNegotiator.ProtocolNegotiator newNegotiator(
          ObjectPool<? extends Executor> offloadExecutorPool) {
        return new InternalProtocolNegotiator.ProtocolNegotiatorAdapter(
            result.negotiator.newNegotiator(offloadExecutorPool));
      }
    }

    return new ServerFactory();
  }
}
