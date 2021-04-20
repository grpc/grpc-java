/*
 * Copyright 2020 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Registry of {@link ManagedChannelProvider}s. The {@link #getDefaultRegistry default instance}
 * loads providers at runtime through the Java service provider mechanism.
 *
 * @since 1.32.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
@ThreadSafe
public final class ManagedChannelRegistry {
  private static final Logger logger = Logger.getLogger(ManagedChannelRegistry.class.getName());
  private static ManagedChannelRegistry instance;

  @GuardedBy("this")
  private final LinkedHashSet<ManagedChannelProvider> allProviders = new LinkedHashSet<>();
  /** Immutable, sorted version of {@code allProviders}. Is replaced instead of mutating. */
  @GuardedBy("this")
  private List<ManagedChannelProvider> effectiveProviders = Collections.emptyList();

  /**
   * Register a provider.
   *
   * <p>If the provider's {@link ManagedChannelProvider#isAvailable isAvailable()} returns
   * {@code false}, this method will throw {@link IllegalArgumentException}.
   *
   * <p>Providers will be used in priority order. In case of ties, providers are used in
   * registration order.
   */
  public synchronized void register(ManagedChannelProvider provider) {
    addProvider(provider);
    refreshProviders();
  }

  private synchronized void addProvider(ManagedChannelProvider provider) {
    Preconditions.checkArgument(provider.isAvailable(), "isAvailable() returned false");
    allProviders.add(provider);
  }

  /**
   * Deregisters a provider.  No-op if the provider is not in the registry.
   *
   * @param provider the provider that was added to the register via {@link #register}.
   */
  public synchronized void deregister(ManagedChannelProvider provider) {
    allProviders.remove(provider);
    refreshProviders();
  }

  private synchronized void refreshProviders() {
    List<ManagedChannelProvider> providers = new ArrayList<>(allProviders);
    // Sort descending based on priority.
    // sort() must be stable, as we prefer first-registered providers
    Collections.sort(providers, Collections.reverseOrder(new Comparator<ManagedChannelProvider>() {
      @Override
      public int compare(ManagedChannelProvider o1, ManagedChannelProvider o2) {
        return o1.priority() - o2.priority();
      }
    }));
    effectiveProviders = Collections.unmodifiableList(providers);
  }

  /**
   * Returns the default registry that loads providers via the Java service loader mechanism.
   */
  public static synchronized ManagedChannelRegistry getDefaultRegistry() {
    if (instance == null) {
      List<ManagedChannelProvider> providerList = ServiceProviders.loadAll(
          ManagedChannelProvider.class,
          getHardCodedClasses(),
          ManagedChannelProvider.class.getClassLoader(),
          new ManagedChannelPriorityAccessor());
      instance = new ManagedChannelRegistry();
      for (ManagedChannelProvider provider : providerList) {
        logger.fine("Service loader found " + provider);
        if (provider.isAvailable()) {
          instance.addProvider(provider);
        }
      }
      instance.refreshProviders();
    }
    return instance;
  }

  /**
   * Returns effective providers, in priority order.
   */
  @VisibleForTesting
  synchronized List<ManagedChannelProvider> providers() {
    return effectiveProviders;
  }

  // For emulating ManagedChannelProvider.provider()
  ManagedChannelProvider provider() {
    List<ManagedChannelProvider> providers = providers();
    return providers.isEmpty() ? null : providers.get(0);
  }

  @VisibleForTesting
  static List<Class<?>> getHardCodedClasses() {
    // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
    // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
    // https://sourceforge.net/p/proguard/bugs/418/
    List<Class<?>> list = new ArrayList<>();
    try {
      list.add(Class.forName("io.grpc.okhttp.OkHttpChannelProvider"));
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find OkHttpChannelProvider", e);
    }
    try {
      list.add(Class.forName("io.grpc.netty.NettyChannelProvider"));
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find NettyChannelProvider", e);
    }
    return Collections.unmodifiableList(list);
  }

  ManagedChannelBuilder<?> newChannelBuilder(String target, ChannelCredentials creds) {
    List<ManagedChannelProvider> providers = providers();
    if (providers.isEmpty()) {
      throw new ProviderNotFoundException("No functional channel service provider found. "
          + "Try adding a dependency on the grpc-okhttp, grpc-netty, or grpc-netty-shaded "
          + "artifact");
    }
    StringBuilder error = new StringBuilder();
    for (ManagedChannelProvider provider : providers()) {
      ManagedChannelProvider.NewChannelBuilderResult result
          = provider.newChannelBuilder(target, creds);
      if (result.getChannelBuilder() != null) {
        return result.getChannelBuilder();
      }
      error.append("; ");
      error.append(provider.getClass().getName());
      error.append(": ");
      error.append(result.getError());
    }
    throw new ProviderNotFoundException(error.substring(2));
  }

  private static final class ManagedChannelPriorityAccessor
      implements ServiceProviders.PriorityAccessor<ManagedChannelProvider> {
    @Override
    public boolean isAvailable(ManagedChannelProvider provider) {
      return provider.isAvailable();
    }

    @Override
    public int getPriority(ManagedChannelProvider provider) {
      return provider.priority();
    }
  }

  /** Thrown when no suitable {@link ManagedChannelProvider} objects can be found. */
  public static final class ProviderNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1;

    public ProviderNotFoundException(String msg) {
      super(msg);
    }
  }
}
