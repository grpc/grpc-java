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

package io.grpc.testing.integration;

import io.grpc.Metadata;

/**
 * Utility methods to support integration testing.
 */
public class ObservabilityTestUtil {

  public static final Metadata.Key<String> RPC_METADATA_KEY
      = Metadata.Key.of("o11y-header", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<byte[]> RPC_METADATA_BIN_KEY
      = Metadata.Key.of("o11y-header-bin", Metadata.BINARY_BYTE_MARSHALLER);

}
