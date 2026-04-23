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

import com.google.common.collect.ImmutableSet;
import dev.cel.runtime.CelVariableResolver;
import java.util.AbstractMap;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * CEL Environment for gRPC xDS matching.
 */
final class GrpcCelEnvironment implements CelVariableResolver {
  private final MatchContext context;

  GrpcCelEnvironment(MatchContext context) {
    this.context = context;
  }

  @Override
  public Optional<Object> find(String name) {
    if (name.equals("request")) {
      return Optional.of(new LazyRequestMap(this));
    }
    return Optional.empty();
  }

  @Nullable
  private Object getRequestField(String requestField) {
    return context.getAttribute(requestField);
  }

  private static final class LazyRequestMap extends AbstractMap<String, Object> {
    private final GrpcCelEnvironment resolver;

    LazyRequestMap(GrpcCelEnvironment resolver) {
      this.resolver = resolver;
    }

    @Override
    public Object get(Object key) {
      if (key instanceof String) {
        return resolver.getRequestField((String) key);
      }
      return null;
    }

    @Override
    public boolean containsKey(Object key) {
      if (!(key instanceof String)) {
        return false;
      }
      String name = (String) key;
      return AttributeProviderRegistry.getDefaultRegistry().get(name) != null
          || resolver.context.getRawAttribute(name) != null;
    }

    @Override
    public Set<String> keySet() {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      builder.addAll(AttributeProviderRegistry.getDefaultRegistry().getRegisteredNames());
      builder.addAll(resolver.context.getRawAttributes().keySet());
      return builder.build();
    }

    @Override
    public int size() {
      return keySet().size();
    }

    @Override
    public boolean isEmpty() {
      return keySet().isEmpty();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      throw new UnsupportedOperationException("LazyRequestMap does not support entrySet");
    }
  }
}
