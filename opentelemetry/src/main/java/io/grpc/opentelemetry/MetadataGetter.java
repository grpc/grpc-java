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


import io.grpc.Metadata;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 *  A TextMapGetter that reads value from gRPC {@link Metadata}. Supports both text and binary
 *  headers. Supporting binary header is an optimization path for GrpcTraceBinContextPropagator
 *  to work around the lack of binary propagator API and thus avoid
 *  base64 (de)encoding when passing data between propagator API interfaces.
 */
final class MetadataGetter implements TextMapGetter<Metadata> {
  private static final Logger logger = Logger.getLogger(MetadataGetter.class.getName());

  private static final MetadataGetter INSTANCE = new MetadataGetter();

  public static MetadataGetter getInstance() {
    return INSTANCE;
  }

  @Override
  public Iterable<String> keys(Metadata carrier) {
    return carrier.keys();
  }

  @Nullable
  @Override
  public String get(@Nullable Metadata carrier, String key) {
    if (carrier == null) {
      logger.log(Level.FINE, "Carrier is null, getting no data");
      return null;
    }
    return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
  }

  @Nullable
  public byte[] getBinary(@Nullable Metadata carrier, String key) {
    if (carrier == null) {
      logger.log(Level.FINE, "Carrier is null, getting no data");
      return null;
    }
    return carrier.get(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
  }
}
