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
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.Audience;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for parsing cluster metadata values.
 *
 * <p>This class maintains a mapping of type URLs to {@link ClusterMetadataValueParser} instances,
 * allowing for the parsing of different metadata types.
 */
final class ClusterMetadataRegistry {
  private static final ClusterMetadataRegistry INSTANCE = new ClusterMetadataRegistry();

  private final Map<String, ClusterMetadataValueParser> supportedParsers = new HashMap<>();

  private ClusterMetadataRegistry() {
    registerParsers(
        new Object[][]{
            {"extensions.filters.http.gcp_authn.v3.Audience", new AudienceMetadataParser()},
            // Add more parsers here as needed
        });
  }

  static ClusterMetadataRegistry getInstance() {
    return INSTANCE;
  }

  ClusterMetadataValueParser findParser(String typeUrl) {
    return supportedParsers.get(typeUrl);
  }

  private void registerParsers(Object[][] parserEntries) {
    for (Object[] entry : parserEntries) {
      String typeUrl = (String) entry[0];
      ClusterMetadataValueParser parser = (ClusterMetadataValueParser) entry[1];
      supportedParsers.put(typeUrl, parser);
    }
  }

  @FunctionalInterface
  interface ClusterMetadataValueParser {
    /**
     * Parses the given {@link Any} object into a specific metadata value.
     *
     * @param any the {@link Any} object to parse.
     * @return the parsed metadata value.
     * @throws InvalidProtocolBufferException if the parsing fails.
     */
    Object parse(Any any) throws InvalidProtocolBufferException;
  }

  /**
   * Parser for Audience metadata type.
   */
  class AudienceMetadataParser implements ClusterMetadataValueParser {
    @Override
    public String parse(Any any) throws InvalidProtocolBufferException {
      if (any.is(Audience.class)) {
        Audience audience = any.unpack(Audience.class);
        String url = audience.getUrl();
        if (url.isEmpty()) {
          throw new InvalidProtocolBufferException("Audience URL is empty.");
        }
        return url;
      } else {
        throw new InvalidProtocolBufferException("Unexpected message type: " + any.getTypeUrl());
      }
    }
  }
}
