/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Registry of {@link NameResolverProvider}s.  The {@link #getDefaultRegistry default instance}
 * loads providers at runtime through the Java service provider mechanism.
 *
 * @since 1.21.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4159")
@ThreadSafe
public final class NameResolverRegistry {
  private static final Logger logger = Logger.getLogger(NameResolverRegistry.class.getName());
  private static NameResolverRegistry instance;

  private final NameResolver.Factory factory = new NameResolverFactory();
  private static final String UNKNOWN_SCHEME = "unknown";
  @GuardedBy("this")
  private String defaultScheme = UNKNOWN_SCHEME;

  @GuardedBy("this")
  private final LinkedHashSet<NameResolverProvider> allProviders = new LinkedHashSet<>();
  /** Generated from {@code allProviders}. Is mapping from scheme key to the highest priority
   * {@link NameResolverProvider}. Is replaced instead of mutating. */
  @GuardedBy("this")
  private ImmutableMap<String, NameResolverProvider> effectiveProviders = ImmutableMap.of();


  /**
   * Register a provider.
   *
   * <p>If the provider's {@link NameResolverProvider#isAvailable isAvailable()} returns
   * {@code false}, this method will throw {@link IllegalArgumentException}.
   *
   * <p>Providers will be used in priority order. In case of ties, providers are used in
   * registration order.
   */
  public synchronized void register(NameResolverProvider provider) {
    addProvider(provider);
    refreshProviders();
  }

  private synchronized void addProvider(NameResolverProvider provider) {
    checkArgument(provider.isAvailable(), "isAvailable() returned false");
    allProviders.add(provider);
  }

  /**
   * Deregisters a provider.  No-op if the provider is not in the registry.
   *
   * @param provider the provider that was added to the register via {@link #register}.
   */
  public synchronized void deregister(NameResolverProvider provider) {
    allProviders.remove(provider);
    refreshProviders();
  }

  private synchronized void refreshProviders() {
    Map<String, NameResolverProvider> refreshedProviders = new HashMap<>();
    int maxPriority = Integer.MIN_VALUE;
    String refreshedDefaultScheme = UNKNOWN_SCHEME;
    // We prefer first-registered providers
    for (NameResolverProvider provider : allProviders) {
      String scheme = provider.getScheme();
      NameResolverProvider existing = refreshedProviders.get(scheme);
      if (existing == null || existing.priority() < provider.priority()) {
        refreshedProviders.put(scheme, provider);
      }
      if (maxPriority < provider.priority()) {
        maxPriority = provider.priority();
        refreshedDefaultScheme = provider.getScheme();
      }
    }
    effectiveProviders = ImmutableMap.copyOf(refreshedProviders);
    defaultScheme = refreshedDefaultScheme;
  }

  /**
   * Returns the default registry that loads providers via the Java service loader mechanism.
   */
  public static synchronized NameResolverRegistry getDefaultRegistry() {
    if (instance == null) {
      List<NameResolverProvider> providerList = ServiceProviders.loadAll(
          NameResolverProvider.class,
          getHardCodedClasses(),
          NameResolverProvider.class.getClassLoader(),
          new NameResolverPriorityAccessor());
      if (providerList.isEmpty()) {
        logger.warning("No NameResolverProviders found via ServiceLoader, including for DNS. This "
            + "is probably due to a broken build. If using ProGuard, check your configuration");
      }
      instance = new NameResolverRegistry();
      for (NameResolverProvider provider : providerList) {
        logger.fine("Service loader found " + provider);
        instance.addProvider(provider);
      }
      instance.refreshProviders();
    }
    return instance;
  }

  /**
   * Returns effective providers map from scheme to the highest priority NameResolverProvider of
   * that scheme.
   */
  @VisibleForTesting
  synchronized Map<String, NameResolverProvider> providers() {
    return effectiveProviders;
  }

  public NameResolver.Factory asFactory() {
    return factory;
  }

  @VisibleForTesting
  static List<Class<?>> getHardCodedClasses() {
    // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
    // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
    // https://sourceforge.net/p/proguard/bugs/418/
    ArrayList<Class<?>> list = new ArrayList<>();
    try {
      list.add(Class.forName("io.grpc.internal.DnsNameResolverProvider"));
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find DNS NameResolver", e);
    }
    return Collections.unmodifiableList(list);
  }

  private final class NameResolverFactory extends NameResolver.Factory {
    @Override
    @Nullable
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      String scheme = targetUri.getScheme();
      if (scheme == null) {
        return null;
      }
      NameResolverProvider provider = providers().get(scheme.toLowerCase(Locale.US));
      return provider == null ? null : provider.newNameResolver(targetUri, args);
    }

    @Override
    public String getDefaultScheme() {
      synchronized (NameResolverRegistry.this) {
        return defaultScheme;
      }
    }
  }

  private static final class NameResolverPriorityAccessor
      implements ServiceProviders.PriorityAccessor<NameResolverProvider> {
    @Override
    public boolean isAvailable(NameResolverProvider provider) {
      return provider.isAvailable();
    }

    @Override
    public int getPriority(NameResolverProvider provider) {
      return provider.priority();
    }
  }
}
