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
 * cancellation events on the server side. An instance of this class is obtained by casting the
 * {@code StreamObserver} passed as an argument to service implementations.
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and interact with the server using a normal client stub.
 */
public abstract class ServerCallStreamObserver<RespT> extends CallStreamObserver<RespT> {

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
   * {@link #onNext}. If the caller is already handling cancellation via polling or cannot
   * substantially benefit from observing cancellation, using a no-op {@code onCancelHandler} is
   * useful just to suppress the {@code onNext()} exception.
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
   */
  public void disableAutoRequest() {
    throw new UnsupportedOperationException();
  }


  /**
   * If {@code true}, indicates that the observer is capable of sending additional messages
   * without requiring excessive buffering internally. This value is just a suggestion and the
   * application is free to ignore it, however doing so may result in excessive buffering within the
   * observer.
   *
   * <p>If {@code false}, the runnable passed to {@link #setOnReadyHandler} will be called after
   * {@code isReady()} transitions to {@code true}.
   */
  @Override
  public abstract boolean isReady();

  /**
   * Set a {@link Runnable} that will be executed every time the stream {@link #isReady()} state
   * changes from {@code false} to {@code true}.  While it is not guaranteed that the same
   * thread will always be used to execute the {@link Runnable}, it is guaranteed that executions
   * are serialized with calls to the 'inbound' {@link StreamObserver}.
   *
   * <p>May only be called during the initial call to the application, before the service returns
   * its {@code StreamObserver}.
   *
   * <p>Because there is a processing delay to deliver this notification, it is possible for
   * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
   * notifications by checking {@code isReady()}'s current value instead of assuming it is now
   * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
   * <em>another</em> {@code onReadyHandler} callback.
   *
   * @param onReadyHandler to call when peer is ready to receive more messages.
   */
  @Override
  public abstract void setOnReadyHandler(Runnable onReadyHandler);

  /**
   * Requests the peer to produce {@code count} more messages to be delivered to the 'inbound'
   * {@link StreamObserver}.
   *
   * <p>This method is safe to call from multiple threads without external synchronization.
   *
   * @param count more messages
   */
  @Override
  public abstract void request(int count);

  /**
   * Sets message compression for subsequent calls to {@link #onNext}.
   *
   * @param enable whether to enable compression.
   */
  @Override
  public abstract void setMessageCompression(boolean enable);

  /**
   * Sets a {@link Runnable} to be executed when the call is closed cleanly from the server's
   * point of view: either {@link #onCompleted()} or {@link #onError(Throwable)} has been called,
   * all the messages and trailing metadata have been sent and the stream has been closed. Note
   * however that the client still may have not received all the messages due to network delay,
   * client crashes, and cancellation races.
   *
   * <p>Exactly one of {@code onCloseHandler} and {@code onCancelHandler} is guaranteed to be called
   * when the RPC terminates.</p>
   *
   * <p>It is guaranteed that execution of {@code onCloseHandler} is serialized with calls to
   * the 'inbound' {@link StreamObserver}. That also means that the callback will be delayed if
   * other callbacks are running.</p>
   *
   * <p>This method may only be called during the initial call to the application, before the
   * service returns its {@link StreamObserver request observer}.</p>
   *
   * @param onCloseHandler to execute when the call has been closed cleanly.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8467")
  public void setOnCloseHandler(Runnable onCloseHandler) {
    throw new UnsupportedOperationException();
  }
}
