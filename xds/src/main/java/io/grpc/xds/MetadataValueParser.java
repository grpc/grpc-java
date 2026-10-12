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

package io.grpc.xds;

import com.google.protobuf.Any;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;

/**
 * Interface for parsing cluster metadata values.
 */
interface MetadataValueParser {

  String getTypeUrl();

  /**
   * Parses the given {@link Any} object into a specific metadata value.
   *
   * @param any the {@link Any} object to parse.
   * @return the parsed metadata value.
   * @throws ResourceInvalidException if the parsing fails.
   */
  Object parse(Any any) throws ResourceInvalidException;
}
