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

package io.grpc.rls;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A strategy for deciding when to throttle requests at the client.
 */
@ThreadSafe
interface Throttler {

  /**
   * Checks if a given request should be throttled by the client. This should be called for every
   * request before allowing it to hit the network. If the returned value is true, the request
   * should be aborted immediately (as if it had been throttled by the server).
   *
   * <p>This updates internal state and should be called exactly once for each request.
   */
  boolean shouldThrottle();

  /**
   * Registers a response received from the backend for a request allowed by shouldThrottle. This
   * should be called for every response received from the backend (i.e., once for each request for
   * which ShouldThrottle returned false). This updates the internal statistics used by
   * shouldThrottle.
   *
   * @param throttled specifies whether the request was throttled by the backend.
   */
  void registerBackendResponse(boolean throttled);

  /**
   * A ThrottledException indicates the call is throttled. This exception is meant to be used by
   * caller of {@link Throttler}, the implementation of Throttler should <strong>not</strong> throw
   * this exception when {@link #shouldThrottle()} is called.
   */
  final class ThrottledException extends RuntimeException {

    static final long serialVersionUID = 1L;

    public ThrottledException() {
      super();
    }

    public ThrottledException(String s) {
      super(s);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }
}
