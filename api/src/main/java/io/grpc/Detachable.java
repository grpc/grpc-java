/*
 * Copyright 2021 The gRPC Authors
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

import java.io.InputStream;

/**
 * An extension of {@link InputStream} that allows the underlying data source to be detached and
 * transferred to a new instance of the same kind. The detached InputStream takes over the
 * ownership of the underlying data source. That's said, the detached InputStream is responsible
 * for releasing its resources after use. The detached InputStream preserves internal states of
 * the underlying data source. Data can be consumed through the detached InputStream as if being
 * continually consumed through the original instance. The original instance discards internal
 * states of detached data source and is no longer consumable as if the data source is exhausted.
 *
 * <p>A normal usage of this API is to extend the lifetime of the data source owned by the
 * original instance for doing extra processing before releasing it. For example, when combined
 * with {@link HasByteBuffer}, a custom {@link io.grpc.MethodDescriptor.Marshaller} can take
 * over the ownership of buffers containing inbound data and perform delayed deserialization.
 */
public interface Detachable {

  /**
   * Detaches the underlying data source from this instance and transfers to an {@link
   * InputStream}. Detaching data from an already-detached instance gives an InputStream with
   * zero bytes of data.
   *
   */
  InputStream detach();
}
