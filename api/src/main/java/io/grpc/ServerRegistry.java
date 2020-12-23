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
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Registry of {@link ServerProvider}s. The {@link #getDefaultRegistry default instance} loads
 * providers at runtime through the Java service provider mechanism.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
@ThreadSafe
public final class ServerRegistry {
  private static final Logger logger = Logger.getLogger(ServerRegistry.class.getName());
  private static ServerRegistry instance;

  @GuardedBy("this")
  private final LinkedHashSet<ServerProvider> allProviders = new LinkedHashSet<>();
  /** Immutable, sorted version of {@code allProviders}. Is replaced instead of mutating. */
  @GuardedBy("this")
  private List<ServerProvider> effectiveProviders = Collections.emptyList();

  /**
   * Register a provider.
   *
   * <p>If the provider's {@link ServerProvider#isAvailable isAvailable()} returns
   * {@code false}, this method will throw {@link IllegalArgumentException}.
   *
   * <p>Providers will be used in priority order. In case of ties, providers are used in
   * registration order.
   */
  public synchronized void register(ServerProvider provider) {
    addProvider(provider);
    refreshProviders();
  }

  private synchronized void addProvider(ServerProvider provider) {
    Preconditions.checkArgument(provider.isAvailable(), "isAvailable() returned false");
    allProviders.add(provider);
  }

  /**
   * Deregisters a provider.  No-op if the provider is not in the registry.
   *
   * @param provider the provider that was added to the register via {@link #register}.
   */
  public synchronized void deregister(ServerProvider provider) {
    allProviders.remove(provider);
    refreshProviders();
  }

  private synchronized void refreshProviders() {
    List<ServerProvider> providers = new ArrayList<>(allProviders);
    // Sort descending based on priority.
    // sort() must be stable, as we prefer first-registered providers
    Collections.sort(providers, Collections.reverseOrder(new Comparator<ServerProvider>() {
      @Override
      public int compare(ServerProvider o1, ServerProvider o2) {
        return o1.priority() - o2.priority();
      }
    }));
    effectiveProviders = Collections.unmodifiableList(providers);
  }

  /**
   * Returns the default registry that loads providers via the Java service loader mechanism.
   */
  public static synchronized ServerRegistry getDefaultRegistry() {
    if (instance == null) {
      List<ServerProvider> providerList = ServiceProviders.loadAll(
          ServerProvider.class,
          Collections.<Class<?>>emptyList(),
          ServerProvider.class.getClassLoader(),
          new ServerPriorityAccessor());
      instance = new ServerRegistry();
      for (ServerProvider provider : providerList) {
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
  synchronized List<ServerProvider> providers() {
    return effectiveProviders;
  }

  // For emulating ServerProvider.provider()
  ServerProvider provider() {
    List<ServerProvider> providers = providers();
    return providers.isEmpty() ? null : providers.get(0);
  }

  ServerBuilder<?> newServerBuilderForPort(int port, ServerCredentials creds) {
    List<ServerProvider> providers = providers();
    if (providers.isEmpty()) {
      throw new ProviderNotFoundException("No functional server found. "
          + "Try adding a dependency on the grpc-netty or grpc-netty-shaded artifact");
    }
    StringBuilder error = new StringBuilder();
    for (ServerProvider provider : providers()) {
      ServerProvider.NewServerBuilderResult result
          = provider.newServerBuilderForPort(port, creds);
      if (result.getServerBuilder() != null) {
        return result.getServerBuilder();
      }
      error.append("; ");
      error.append(provider.getClass().getName());
      error.append(": ");
      error.append(result.getError());
    }
    throw new ProviderNotFoundException(error.substring(2));
  }

  private static final class ServerPriorityAccessor
      implements ServiceProviders.PriorityAccessor<ServerProvider> {
    @Override
    public boolean isAvailable(ServerProvider provider) {
      return provider.isAvailable();
    }

    @Override
    public int getPriority(ServerProvider provider) {
      return provider.priority();
    }
  }

  /** Thrown when no suitable {@link ServerProvider} objects can be found. */
  public static final class ProviderNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1;

    public ProviderNotFoundException(String msg) {
      super(msg);
    }
  }
}
