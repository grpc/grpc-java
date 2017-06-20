/*
 * Copyright 2016, gRPC Authors All rights reserved.
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
 * A refinement of {@link CallStreamObserver} that allows for lower-level interaction with
 * client calls.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1788")
public abstract class ClientCallStreamObserver<V> extends CallStreamObserver<V> {
    /**
     * Prevent any further processing for this {@code ClientCallStreamObserver}. No further messages will be received.
     * The server is informed of cancellations, but may not stop processing the call. Cancelling an already
     * {@code cancel()}ed {@code ClientCallStreamObserver} has no effect.
     */
    public abstract void cancel();
}
