/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.stub;

import io.grpc.ExperimentalApi;

/**
 * A refinement of {@link CallStreamObserver} to allows for interaction with call
 * cancellation events on the server side.
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and interact with the server using a normal client stub.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1788")
public abstract class ServerCallStreamObserver<V> extends CallStreamObserver<V> {

  /**
   * Returns {@code true} when the call is cancelled and the server is encouraged to abort
   * processing to save resources, since the client will not be processing any further methods.
   * Cancellations can be caused by timeouts, explicit cancellation by client, network errors, and
   * similar.
   *
   * <p>This method may safely be called concurrently from multiple threads.
   */
  public abstract boolean isCancelled();

  /**
   * Sets a {@link Runnable} to be called if the call is cancelled and the server is encouraged to
   * abort processing to save resources, since the client will not process any further messages.
   * Cancellations can be caused by timeouts, explicit cancellation by the client, network errors,
   * etc.
   *
   * <p>It is guaranteed that execution of the {@link Runnable} is serialized with calls to the
   * 'inbound' {@link StreamObserver}. That also means that the callback will be delayed if other
   * callbacks are running; if one of those other callbacks runs for a significant amount of time
   * it can poll {@link #isCancelled()}, which is not delayed.
   *
   * <p>This method may only be called during the initial call to the application, before the
   * service returns its {@code StreamObserver}.
   *
   * <p>Setting the onCancelHandler will suppress the on-cancel exception thrown by
   * {@link #onNext}.
   *
   * @param onCancelHandler to call when client has cancelled the call.
   */
  public abstract void setOnCancelHandler(Runnable onCancelHandler);

  /**
   * Sets the compression algorithm to use for the call. May only be called before sending any
   * messages. Default gRPC servers support the "gzip" compressor.
   *
   * <p>It is safe to call this even if the client does not support the compression format chosen.
   * The implementation will handle negotiation with the client and may fall back to no compression.
   *
   * @param compression the compression algorithm to use.
   * @throws IllegalArgumentException if the compressor name can not be found.
   */
  public abstract void setCompression(String compression);

  /**
   * Swaps to manual flow control where no message will be delivered to {@link
   * StreamObserver#onNext(Object)} unless it is {@link #request request()}ed.
   *
   * <p>It may only be called during the initial call to the application, before the service returns
   * its {@code StreamObserver}.
   *
   * <p>Note that for cases where the message is received before the service handler is invoked,
   * this method will have no effect. This is true for:
   *
   * <ul>
   *   <li>{@link io.grpc.MethodDescriptor.MethodType#UNARY} operations.</li>
   *   <li>{@link io.grpc.MethodDescriptor.MethodType#SERVER_STREAMING} operations.</li>
   * </ul>
   * </p>
   *
   * <p>This API is still a work in-progress and may change in the future.
   */
  public void disableAutoRequest() {
    throw new UnsupportedOperationException();
  }
}
