/*
 * Copyright 2015 The gRPC Authors
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

import io.grpc.internal.ObjectPool;
import io.netty.channel.ChannelHandler;
import io.netty.util.AsciiString;
import java.util.concurrent.Executor;

/**
 * An class that provides a Netty handler to control protocol negotiation.
 */
interface ProtocolNegotiator {

  /**
   * The HTTP/2 scheme to be used when sending {@code HEADERS}.
   */
  AsciiString scheme();

  /**
   * Creates a new handler to control the protocol negotiation. Once the negotiation has completed
   * successfully, the provided handler is installed. Must call {@code
   * grpcHandler.onHandleProtocolNegotiationCompleted()} at certain point if the negotiation has
   * completed successfully.
   */
  ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler);

  /**
   * Releases resources held by this negotiator. Called when the Channel transitions to terminated
   * or when InternalServer is shutdown (depending on client or server). That means handlers
   * returned by {@link #newHandler} can outlive their parent negotiator on server-side, but not
   * on client-side.
   */
  void close();

  interface ClientFactory {
    /** Creates a new negotiator. */
    ProtocolNegotiator newNegotiator();

    /** Returns the implicit port to use if no port was specified explicitly by the user. */
    int getDefaultPort();
  }

  interface ServerFactory {
    /**
     * Creates a new negotiator.
     *
     * @param offloadExecutorPool an executor pool for time-consuming tasks
     */
    ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool);
  }
}
