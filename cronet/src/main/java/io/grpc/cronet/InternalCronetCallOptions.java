/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.cronet;

import io.grpc.CallOptions;
import io.grpc.Internal;
import java.util.Collection;
import java.util.Collections;

/**
 * Internal accessor class for call options using with the Cronet transport. This is intended for
 * usage internal to the gRPC team. If you *really* think you need to use this, contact the gRPC
 * team first.
 */
@Internal
public final class InternalCronetCallOptions {

  // Prevent instantiation
  private InternalCronetCallOptions() {}

  public static CallOptions withAnnotation(CallOptions callOptions, Object annotation) {
    return CronetClientStream.withAnnotation(callOptions, annotation);
  }

  public static CallOptions withReadBufferSize(CallOptions callOptions, int size) {
    return callOptions.withOption(CronetClientStream.CRONET_READ_BUFFER_SIZE_KEY, size);
  }

  /**
   * Returns Cronet read buffer size for gRPC included in the given {@code callOptions}. Read
   * buffer can be customized via {@link #withReadBufferSize(CallOptions, int)}.
   */
  public static int getReadBufferSize(CallOptions callOptions) {
    return callOptions.getOption(CronetClientStream.CRONET_READ_BUFFER_SIZE_KEY);
  }

  /**
   * Returns Cronet annotations for gRPC included in the given {@code callOptions}. Annotations
   * are attached via {@link #withAnnotation(CallOptions, Object)}.
   */
  public static Collection<Object> getAnnotations(CallOptions callOptions) {
    Collection<Object> annotations =
        callOptions.getOption(CronetClientStream.CRONET_ANNOTATIONS_KEY);
    if (annotations == null) {
      annotations = Collections.emptyList();
    }
    return annotations;
  }
}
