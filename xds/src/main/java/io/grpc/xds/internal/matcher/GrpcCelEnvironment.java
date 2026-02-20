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

import com.google.common.base.Splitter;
import dev.cel.runtime.CelVariableResolver;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * CEL Environment for gRPC xDS matching.
 */
final class GrpcCelEnvironment implements CelVariableResolver {
  private static final Splitter SPLITTER = Splitter.on('.').limit(2);
  private final MatchContext context;

  GrpcCelEnvironment(MatchContext context) {
    this.context = context;
  }

  @Override
  public Optional<Object> find(String name) {
    if (name.equals("request")) {
      return Optional.of(new LazyRequestMap(this));
    }
    List<String> components = SPLITTER.splitToList(name);
    if (components.size() == 2 && components.get(0).equals("request")) {
      return Optional.ofNullable(getRequestField(components.get(1)));
    }
    return Optional.empty();
  }

  @Nullable
  private Object getRequestField(String requestField) {
    switch (requestField) {
      case "headers": return new HeadersWrapper(context);
      case "host": return orEmpty(context.getHost());
      case "id": return orEmpty(context.getId());
      case "method": return or(context.getMethod(), "POST");
      case "path":
      case "url_path":
        return orEmpty(context.getPath());
      case "query": return "";
      case "scheme": return "";
      case "protocol": return "";
      case "time": return null;
      case "referer": return getHeader("referer");
      case "useragent": return getHeader("user-agent");

      default:
        return null; 
    }
  }

  private String getHeader(String key) {
    io.grpc.Metadata metadata = context.getMetadata();
    Iterable<String> values = metadata.getAll(
        io.grpc.Metadata.Key.of(key, io.grpc.Metadata.ASCII_STRING_MARSHALLER));
    if (values == null) {
      return "";
    }
    return String.join(",", values);
  }
  
  private static String orEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }

  private static String or(@Nullable String s, String def) {
    return s == null ? def : s;
  }

  private static final class LazyRequestMap extends java.util.AbstractMap<String, Object> {
    private static final java.util.Set<String> KNOWN_KEYS = 
        com.google.common.collect.ImmutableSet.of(
            "headers", "host", "id", "method", "path", "url_path", "query", "scheme", "protocol", 
            "referer", "useragent", "time"
        );
    private final GrpcCelEnvironment resolver;

    LazyRequestMap(GrpcCelEnvironment resolver) {
      this.resolver = resolver;
    }

    @Override
    public Object get(Object key) {
      if (key instanceof String) {
        Object val = resolver.getRequestField((String) key);
        if (val == null) {
          return null;
        }
        return val;
      }
      return null;
    }

    @Override
    public boolean containsKey(Object key) {
      boolean contains = key instanceof String && KNOWN_KEYS.contains(key);
      return contains;
    }

    @Override
    public java.util.Set<String> keySet() {
      return KNOWN_KEYS;
    }

    @Override
    public int size() {
      return KNOWN_KEYS.size();
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public java.util.Set<Entry<String, Object>> entrySet() {
      throw new UnsupportedOperationException("LazyRequestMap does not support entrySet");
    }
  }
}
