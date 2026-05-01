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
   * Starts asynchronous processing of an incoming call.
   *
   * <p>Callers of this method transfer their ownership of the non-thread-safe {@link ServerCall}
   * and {@link Metadata} arguments to the {@link ServerCallHandler} implementation for processing.
   * Ownership means that the implementation may invoke methods on {@code call} and {@code headers}
   * while {@link #startCall} runs and at any time after it returns normally. On the other hand, if
   * {@link #startCall} throws, ownership of {@code call} and {@code headers} reverts to the caller
   * and the implementation loses the right to call methods on these objects (from some other
   * thread, say).
   *
   * <p>Ownership also includes the responsibility to eventually close {@code call}. In particular,
   * if {@link #startCall} throws an exception, the caller must handle it by closing {@code call}
   * with an error. Since {@code call} can only be closed once, an implementation can report errors
   * either to {@link ServerCall#close} for itself or by throwing an exception, but not both.
   *
   * <p>Returns a non-{@code null} listener for the incoming call. Callers of this method must
   * arrange for events associated with {@code call} to be delivered there.
   *
   * @param call object for responding to the remote client.
   * @param headers request headers received from the client but open to modification by an owner
   * @return listener for processing incoming request messages for {@code call}
   */
  ServerCall.Listener<RequestT> startCall(
      ServerCall<RequestT, ResponseT> call,
      Metadata headers);
}
