/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Registry of {@link LoadBalancerProvider}s.  The {@link #getDefaultRegistry default instance}
 * loads providers at runtime through the Java service provider mechanism.
 *
 * @since 1.17.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@ThreadSafe
public final class LoadBalancerRegistry {
  private static final Logger logger = Logger.getLogger(LoadBalancerRegistry.class.getName());
  private static LoadBalancerRegistry instance;
  private static final Iterable<Class<?>> HARDCODED_CLASSES = getHardCodedClasses();

  private final LinkedHashMap<String, LoadBalancerProvider> providers =
      new LinkedHashMap<String, LoadBalancerProvider>();

  /**
   * Register a provider.  If successfully registered, this provider can be fetched by its
   * {@link LoadBalancerProvider#getPolicyName policy name} via {@link #getProvider}.  If a provider
   * with the same policy name has already been registered, only the provider with the higher
   * {@link LoadBalancerProvider#getPriority priority}, or the one registered earlier if their
   * priorities are the same, will be kept in the registry.
   *
   * @return {@code OK} if successfully registered. {@code UNAVAILABLE} if the provider's
   *         {@link LoadBalancerProvider#isAvailable isAvailable()} returned {@code false}.
   *         {@code ALREADY_EXISTS} if a provider with the same policy name and a higher priority
   *         has already been registered.  {@code FAILED_PRECONDITION} if a provider with the same
   *         policy name and an equal priority has already been registered.
   */
  public synchronized Status register(LoadBalancerProvider provider) {
    if (!provider.isAvailable()) {
      return Status.UNAVAILABLE.withDescription("isAvailable() returned false");
    }
    String policy = provider.getPolicyName();
    LoadBalancerProvider existing = providers.get(policy);
    if (existing == null) {
      providers.put(policy, provider);
      return Status.OK;
    } else {
      if (existing.getPriority() < provider.getPriority()) {
        providers.put(policy, provider);
        return Status.OK;
      } else if (existing.getPriority() > provider.getPriority()) {
        return Status.ALREADY_EXISTS.withDescription(
            existing + " with higher priority already registered");
      } else {
        return Status.FAILED_PRECONDITION.withDescription(
            existing + " with the same priority already registered");
      }
    }
  }

  /**
   * Returns the default registry that loads providers via the Java service loader mechanism.
   */
  public static synchronized LoadBalancerRegistry getDefaultRegistry() {
    if (instance == null) {
      List<LoadBalancerProvider> providerList = ServiceProviders.loadAll(
          LoadBalancerProvider.class,
          HARDCODED_CLASSES,
          LoadBalancerProvider.class.getClassLoader(),
          new LoadBalancerPriorityAccessor());
      instance = new LoadBalancerRegistry();
      for (LoadBalancerProvider provider : providerList) {
        logger.fine("Found " + provider);
        Status status = instance.register(provider);
        switch (status.getCode()) {
          case OK:
            logger.fine(provider + " successfully registered");
            break;
          case FAILED_PRECONDITION:
            logger.warning(provider + " failed to register because " + status.getDescription());
            break;
          default:
            logger.fine(provider + " didn't register because " + status.getDescription());
        }
      }
    }
    return instance;
  }

  /**
   * Returns the provider for the given load-balancing policy, or {@code null} if no suitable
   * provider can be found.  Each provider declares its policy name via {@link
   * LoadBalancerProvider#getPolicyName}.
   */
  @Nullable
  public synchronized LoadBalancerProvider getProvider(String policy) {
    return providers.get(checkNotNull(policy, "policy"));
  }

  /**
   * Returns effective providers in a new map.
   */
  @VisibleForTesting
  synchronized Map<String, LoadBalancerProvider> providers() {
    return new LinkedHashMap<String, LoadBalancerProvider>(providers);
  }

  @VisibleForTesting
  static List<Class<?>> getHardCodedClasses() {
    // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
    // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
    // https://sourceforge.net/p/proguard/bugs/418/
    ArrayList<Class<?>> list = new ArrayList<Class<?>>();
    try {
      list.add(Class.forName("io.grpc.internal.PickFirstLoadBalancerProvider"));
    } catch (ClassNotFoundException e) {
      logger.log(Level.WARNING, "Unable to find pick-first LoadBalancer", e);
    }
    try {
      list.add(Class.forName("io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider"));
    } catch (ClassNotFoundException e) {
      // Since hard-coded list is only used in Android environment, and we don't expect round-robin
      // to be actually used there, we log it as a lower level.
      logger.log(Level.FINE, "Unable to find round-robin LoadBalancer", e);
    }
    return Collections.unmodifiableList(list);
  }

  private static final class LoadBalancerPriorityAccessor
      implements ServiceProviders.PriorityAccessor<LoadBalancerProvider> {

    LoadBalancerPriorityAccessor() {}

    @Override
    public boolean isAvailable(LoadBalancerProvider provider) {
      return provider.isAvailable();
    }

    @Override
    public int getPriority(LoadBalancerProvider provider) {
      return provider.getPriority();
    }
  }
}
