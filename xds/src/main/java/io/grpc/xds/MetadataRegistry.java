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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.grpc.xds.GcpAuthenticationFilter.AudienceMetadataParser;
import io.grpc.xds.XdsEndpointResource.AddressMetadataParser;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import io.grpc.xds.internal.ProtobufJsonConverter;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for parsing cluster metadata values.
 *
 * <p>This class maintains a mapping of type URLs to {@link MetadataValueParser} instances,
 * allowing for the parsing of different metadata types.
 */
final class MetadataRegistry {
  private static final MetadataRegistry INSTANCE = new MetadataRegistry();

  private final Map<String, MetadataValueParser> supportedParsers = new HashMap<>();

  private MetadataRegistry() {
    registerParser(new AudienceMetadataParser());
    registerParser(new AddressMetadataParser());
  }

  static MetadataRegistry getInstance() {
    return INSTANCE;
  }

  MetadataValueParser findParser(String typeUrl) {
    return supportedParsers.get(typeUrl);
  }

  @VisibleForTesting
  void registerParser(MetadataValueParser parser) {
    supportedParsers.put(parser.getTypeUrl(), parser);
  }

  void removeParser(MetadataValueParser parser) {
    supportedParsers.remove(parser.getTypeUrl());
  }

  /**
   * Parses cluster metadata into a structured map.
   *
   * <p>Values in {@code typed_filter_metadata} take precedence over
   * {@code filter_metadata} when keys overlap, following Envoy API behavior. See
   * <a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/config/core/v3/base.proto#L217-L259">
   *   Envoy metadata documentation </a> for details.
   *
   * @param metadata the {@link Metadata} containing the fields to parse.
   * @return an immutable map of parsed metadata.
   * @throws ResourceInvalidException if parsing {@code typed_filter_metadata} fails.
   */
  public ImmutableMap<String, Object> parseMetadata(Metadata metadata)
      throws ResourceInvalidException {
    ImmutableMap.Builder<String, Object> parsedMetadata = ImmutableMap.builder();

    // Process typed_filter_metadata
    for (Map.Entry<String, Any> entry : metadata.getTypedFilterMetadataMap().entrySet()) {
      String key = entry.getKey();
      Any value = entry.getValue();
      MetadataValueParser parser = findParser(value.getTypeUrl());
      if (parser != null) {
        try {
          Object parsedValue = parser.parse(value);
          parsedMetadata.put(key, parsedValue);
        } catch (ResourceInvalidException e) {
          throw new ResourceInvalidException(
              String.format("Failed to parse metadata key: %s, type: %s. Error: %s",
                  key, value.getTypeUrl(), e.getMessage()), e);
        }
      }
    }
    // building once to reuse in the next loop
    ImmutableMap<String, Object> intermediateParsedMetadata = parsedMetadata.build();

    // Process filter_metadata for remaining keys
    for (Map.Entry<String, Struct> entry : metadata.getFilterMetadataMap().entrySet()) {
      String key = entry.getKey();
      if (!intermediateParsedMetadata.containsKey(key)) {
        Struct structValue = entry.getValue();
        Object jsonValue = ProtobufJsonConverter.convertToJson(structValue);
        parsedMetadata.put(key, jsonValue);
      }
    }

    return parsedMetadata.build();
  }

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
}
