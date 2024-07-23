/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.opentelemetry;


import io.opentelemetry.api.trace.SpanContext;

/**
 * This is a helper class for SpanContext propagation on the wire using binary encoding.
 */
abstract class BinaryFormat {
  public byte[] toByteArray(SpanContext spanContext) {
    throw new UnsupportedOperationException("not implemented");
  }

  public  SpanContext fromByteArray(byte[] bytes) {
    throw new UnsupportedOperationException("not implemented");
  }
}
