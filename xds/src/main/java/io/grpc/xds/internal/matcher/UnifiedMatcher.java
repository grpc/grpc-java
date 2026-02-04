/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.matcher;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.xds.core.v3.TypedExtensionConfig;
import com.github.xds.type.matcher.v3.Matcher;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import javax.annotation.Nullable;

/**
 * Represents a compiled xDS Matcher.
 */
public abstract class UnifiedMatcher {

  // Supported Extension Type URLs per gRFC A106
  private static final String TYPE_URL_HTTP_HEADER_INPUT =
      "type.googleapis.com/envoy.type.matcher.v3.HttpRequestHeaderMatchInput";
  private static final String TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT =
      "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";
  static final int MAX_RECURSION_DEPTH = 16;

  @Nullable
  public abstract MatchResult match(MatchContext context, int depth);

  static MatchInput<MatchContext> resolveInput(TypedExtensionConfig config) {
    String typeUrl = config.getTypedConfig().getTypeUrl();
    try {
      if (typeUrl.equals(TYPE_URL_HTTP_HEADER_INPUT)) {
        HttpRequestHeaderMatchInput proto = config.getTypedConfig()
            .unpack(HttpRequestHeaderMatchInput.class);
        return new HeaderMatchInput(proto.getHeaderName());
      } else if (typeUrl.equals(TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT)) {
        return new MatchInput<MatchContext>() {
          @Override
          public Object apply(MatchContext context) {
            return new GrpcCelEnvironment(context);
          }
        };
      }
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid input config: " + typeUrl, e);
    }
    throw new IllegalArgumentException("Unsupported input type: " + typeUrl);
  }

  private static final class HeaderMatchInput implements MatchInput<MatchContext> {
    private final String headerName;
    
    HeaderMatchInput(String headerName) {
      this.headerName = checkNotNull(headerName, "headerName");
      if (headerName.isEmpty() || headerName.length() >= 16384) {
        throw new IllegalArgumentException(
            "Header name length must be in range [1, 16384): " + headerName.length());
      }
      if (!headerName.equals(headerName.toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalArgumentException("Header name must be lowercase: " + headerName);
      }
      try {
        if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
        } else {
          Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid header name: " + headerName, e);
      }
    }
    
    @Override
    public String apply(MatchContext context) {
      if ("te".equals(headerName)) {
        return null;
      }
      if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Iterable<byte[]> values = context.getMetadata().getAll(
            Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER));
        if (values == null) {
          return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (byte[] value : values) {
          if (!first) {
            sb.append(",");
          }
          first = false;
          sb.append(com.google.common.io.BaseEncoding.base64().encode(value));
        }
        return sb.toString();
      }
      Metadata metadata = context.getMetadata();
      Iterable<String> values = metadata.getAll(
          Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
      if (values == null) {
        return null;
      }
      return String.join(",", values);
    }
  }

  /**
   * Parses a proto Matcher into a UnifiedMatcher.
   *
   * @param proto the proto matcher
   * @param actionValidator a predicate that returns true if the action type URL is supported
   */
  public static UnifiedMatcher fromProto(Matcher proto,
      java.util.function.Predicate<String> actionValidator) {
    checkRecursionDepth(proto, 0);
    Matcher.OnMatch onNoMatch = proto.hasOnNoMatch() ? proto.getOnNoMatch() : null;
    if (proto.hasMatcherList()) {
      return new MatcherList(proto.getMatcherList(), onNoMatch, actionValidator);
    } else if (proto.hasMatcherTree()) {
      return new MatcherTree(proto.getMatcherTree(), onNoMatch, actionValidator);
    }
    return new NoOpMatcher(onNoMatch, actionValidator);
  }

  /**
   * Parses a proto Matcher into a UnifiedMatcher, allowing all actions.
   */
  public static UnifiedMatcher fromProto(Matcher proto) {
    return fromProto(proto, (typeUrl) -> true);
  }

  private static void checkRecursionDepth(Matcher proto, int currentDepth) {
    if (currentDepth > MAX_RECURSION_DEPTH) {
      throw new IllegalArgumentException(
          "Matcher tree depth exceeds limit of " + MAX_RECURSION_DEPTH);
    }
    if (proto.hasMatcherList()) {
      for (Matcher.MatcherList.FieldMatcher fm : proto.getMatcherList().getMatchersList()) {
        if (fm.hasOnMatch() && fm.getOnMatch().hasMatcher()) {
          checkRecursionDepth(fm.getOnMatch().getMatcher(), currentDepth + 1);
        }
      }
    } else if (proto.hasMatcherTree()) {
      Matcher.MatcherTree tree = proto.getMatcherTree();
      if (tree.hasExactMatchMap()) {
        for (Matcher.OnMatch onMatch : tree.getExactMatchMap().getMapMap().values()) {
          if (onMatch.hasMatcher()) {
            checkRecursionDepth(onMatch.getMatcher(), currentDepth + 1);
          }
        }
      } else if (tree.hasPrefixMatchMap()) {
        for (Matcher.OnMatch onMatch : tree.getPrefixMatchMap().getMapMap().values()) {
          if (onMatch.hasMatcher()) {
            checkRecursionDepth(onMatch.getMatcher(), currentDepth + 1);
          }
        }
      }
    }
    if (proto.hasOnNoMatch() && proto.getOnNoMatch().hasMatcher()) {
      checkRecursionDepth(proto.getOnNoMatch().getMatcher(), currentDepth + 1);
    }
  }
  
  private static final class NoOpMatcher extends UnifiedMatcher {
    @Nullable private final OnMatch onNoMatch;
    
    NoOpMatcher(@Nullable Matcher.OnMatch onNoMatchProto,
        java.util.function.Predicate<String> actionValidator) {
      if (onNoMatchProto != null) {
        this.onNoMatch = new OnMatch(onNoMatchProto, actionValidator);
      } else {
        this.onNoMatch = null;
      }
    }

    @Override
    public MatchResult match(MatchContext context, int depth) {
      if (depth > MAX_RECURSION_DEPTH) {
        return MatchResult.noMatch();
      }
      if (onNoMatch != null) {
        return onNoMatch.evaluate(context, depth);
      }
      return MatchResult.noMatch();
    }
  }
}
