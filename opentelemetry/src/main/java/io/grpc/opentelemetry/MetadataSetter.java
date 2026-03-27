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


import com.google.common.io.BaseEncoding;
import io.grpc.Metadata;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 *  A {@link TextMapSetter} that sets value to gRPC {@link Metadata}. Supports both text and binary
 *  headers. Supporting binary header is an optimization path for GrpcTraceBinContextPropagator
 *  to work around the lack of binary propagator API and thus avoid
 *  base64 (de)encoding when passing data between propagator API interfaces.
 */
final class MetadataSetter implements TextMapSetter<Metadata> {
  private static final Logger logger = Logger.getLogger(MetadataSetter.class.getName());
  private static final MetadataSetter INSTANCE = new MetadataSetter();

  public static MetadataSetter getInstance() {
    return INSTANCE;
  }

  @Override
  public void set(@Nullable Metadata carrier, String key, String value) {
    logger.log(Level.FINE, "Setting text trace header key={0} value={1}",
        new Object[]{key, value});
    if (carrier == null) {
      logger.log(Level.FINE, "Carrier is null, setting no data");
      return;
    }
    try {
      if (key.equals("grpc-trace-bin")) {
        carrier.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER),
            BaseEncoding.base64().decode(value));
      } else {
        carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
      }
    } catch (Exception e) {
      logger.log(Level.INFO, String.format("Failed to set metadata, key=%s", key), e);
    }
  }

  void set(@Nullable Metadata carrier, String key, byte[] value) {
    logger.log(Level.FINE, "Setting binary trace header key={0} value={1}",
        new Object[]{key, Arrays.toString(value)});
    if (carrier == null) {
      logger.log(Level.FINE, "Carrier is null, setting no data");
      return;
    }
    if (!key.equals("grpc-trace-bin")) {
      logger.log(Level.INFO, "Only support 'grpc-trace-bin' binary header. Set no data");
      return;
    }
    try {
      carrier.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), value);
    } catch (Exception e) {
      logger.log(Level.INFO, String.format("Failed to set metadata key=%s", key), e);
    }
  }
}
