/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface to initiate processing of incoming remote calls. Advanced applications and generated
 * code will implement this interface to allows {@link Server}s to invoke service methods.
 */
@ThreadSafe
public interface ServerCallHandler<RequestT, ResponseT> {
  /**
   * Starts the next stage of {@code call} processing.
   *
   * <p>Returns a non-{@code null} listener for the incoming call. Callers must arrange for events
   * associated with {@code call} to be delivered there.
   *
   * <p>Callers of this method transfer their ownership of non-thread-safe {@link ServerCall} and
   * {@link Metadata} arguments to this {@link ServerCallHandler} for the next stage of asynchronous
   * processing. Ownership means that an implementation of {@link #startCall} may invoke methods on
   * {@code call} and {@code headers} while it runs and at any time after it returns normally. On
   * the other hand, if {@link #startCall} throws, ownership of {@code call} and {@code headers}
   * reverts to the caller and the {@link ServerCallHandler} implementation must not call any method
   * on these objects (from some other thread, say).
   *
   * <p>If {@link #startCall} throws an exception, the caller must close {@code call} with an error.
   *
   * @param call object for responding to the remote client.
   * @return listener for processing incoming request messages for {@code call}
   */
  ServerCall.Listener<RequestT> startCall(
      ServerCall<RequestT, ResponseT> call,
      Metadata headers);
}
