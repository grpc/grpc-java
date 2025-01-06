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
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.xds.GcpAuthenticationFilter.AudienceMetadataParser;
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

  interface MetadataValueParser {

    String getTypeUrl();

    /**
     * Parses the given {@link Any} object into a specific metadata value.
     *
     * @param any the {@link Any} object to parse.
     * @return the parsed metadata value.
     * @throws InvalidProtocolBufferException if the parsing fails.
     */
    Object parse(Any any) throws InvalidProtocolBufferException;
  }
}
