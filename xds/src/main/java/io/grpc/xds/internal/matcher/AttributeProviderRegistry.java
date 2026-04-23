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
import io.grpc.Metadata;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Registry for {@link AttributeProvider}s.
 */
public final class AttributeProviderRegistry {
  private static final AttributeProviderRegistry DEFAULT_INSTANCE = new AttributeProviderRegistry();

  private final Map<String, AttributeProvider> providers = new ConcurrentHashMap<>();

  private AttributeProviderRegistry() {
    register(new HeadersProvider());
    register(new HostProvider());
    register(new IdProvider());
    register(new MethodProvider());
    register(new PathProvider());
    register(new QueryProvider());
    register(new SchemeProvider());
    register(new ProtocolProvider());
    register(new TimeProvider());
    register(new RefererProvider());
    register(new UserAgentProvider());
  }

  public static AttributeProviderRegistry getDefaultRegistry() {
    return DEFAULT_INSTANCE;
  }

  public void register(AttributeProvider provider) {
    for (String name : provider.names()) {
      providers.put(name, provider);
    }
  }

  @Nullable
  public AttributeProvider get(String name) {
    return providers.get(name);
  }

  public Set<String> getRegisteredNames() {
    return Collections.unmodifiableSet(providers.keySet());
  }

  private static String orEmpty(@Nullable Object s) {
    return s == null ? "" : s.toString();
  }

  private static String or(@Nullable Object s, String def) {
    return s == null ? def : s.toString();
  }

  private static String getHeader(MatchContext context, String key) {
    Metadata metadata = context.getMetadata();
    Iterable<String> values = metadata.getAll(
        Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
    if (values == null) {
      return "";
    }
    return String.join(",", values);
  }

  private static final class HeadersProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("headers");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return new HeadersWrapper(context);
    }
  }

  private static final class HostProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("host");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return orEmpty(context.getRawAttribute("host"));
    }
  }

  private static final class IdProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("id");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return orEmpty(context.getRawAttribute("id"));
    }
  }

  private static final class MethodProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("method");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return or(context.getRawAttribute("method"), "POST");
    }
  }

  private static final class PathProvider implements AttributeProvider {
    private static final Set<String> NAMES = ImmutableSet.of("path", "url_path");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return orEmpty(context.getRawAttribute("path"));
    }
  }

  private static final class QueryProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("query");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return "";
    }
  }

  private static final class SchemeProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("scheme");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return "";
    }
  }

  private static final class ProtocolProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("protocol");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return "";
    }
  }

  private static final class TimeProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("time");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    @Nullable
    public Object get(MatchContext context) {
      return null;
    }
  }

  private static final class RefererProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("referer");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return getHeader(context, "referer");
    }
  }

  private static final class UserAgentProvider implements AttributeProvider {
    private static final Set<String> NAMES = Collections.singleton("useragent");

    @Override
    public Iterable<String> names() {
      return NAMES;
    }

    @Override
    public Object get(MatchContext context) {
      return getHeader(context, "user-agent");
    }
  }
}
