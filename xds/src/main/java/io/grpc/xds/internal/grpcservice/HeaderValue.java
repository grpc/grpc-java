/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.grpcservice;

import com.google.auto.value.AutoValue;
import com.google.protobuf.ByteString;
import java.util.Optional;

/**
 * Represents a header to be mutated or added as part of xDS configuration.
 * Avoids direct dependency on Envoy's proto objects while providing an immutable representation.
 */
@AutoValue
public abstract class HeaderValue {

  public static HeaderValue create(String key, String value) {
    return new AutoValue_HeaderValue(key, Optional.of(value), Optional.empty());
  }

  public static HeaderValue create(String key, ByteString rawValue) {
    return new AutoValue_HeaderValue(key, Optional.empty(), Optional.of(rawValue));
  }


  public abstract String key();

  public abstract Optional<String> value();

  public abstract Optional<ByteString> rawValue();
}
