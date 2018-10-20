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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provider of {@link LoadBalancer}s.  Each provider is bounded to a load-balancing policy name.
 */
@ExperimentalApi("TODO")
public abstract class LoadBalancerProvider extends LoadBalancer.Factory {
  private static final Logger logger = Logger.getLogger(LoadBalancerProvider.class.getName());
  
  @VisibleForTesting
  static final Iterable<Class<?>> HARDCODED_CLASSES = getHardCodedClasses();

  private static final Map<String, LoadBalancerProvider> providers;

  static {
    List<LoadBalancerProvider> providerList = ServiceProviders.loadAll(
        LoadBalancerProvider.class,
        HARDCODED_CLASSES,
        LoadBalancerProvider.class.getClassLoader(),
        new LoadBalancerPriorityAccessor());
    HashMap<String, LoadBalancerProvider> providerMap = new HashMap<>();
    for (LoadBalancerProvider provider : providerList) {
      String policy = provider.getPolicyName();
      LoadBalancerProvider existing = providerMap.get(policy);
      if (existing == null) {
        logger.fine("Found " + provider);
        providerMap.put(policy, provider);
      } else {
        if (existing.getPriority() < provider.getPriority()) {
          logger.fine(provider + " overrides " + existing + " because of higher priority");
          providerMap.put(policy, provider);
        } else if (existing.getPriority() > provider.getPriority()) {
          logger.fine(provider + " doesn't override " + existing + " because of lower priority");
        } else {
          logger.warning(
              provider + " and " + existing + " has the same priority. "
              + existing + " is selected for this time, but it may not always be the case. "
              + "You should make them differ in either policy name or priority, or remove "
              + "one of them from your classpath");
        }
      }
    }
    providers = Collections.unmodifiableMap(providerMap);
  }

  /**
   * Returns effective providers.
   */
  @VisibleForTesting
  static Map<String, LoadBalancerProvider> providers() {
    return providers;
  }

  @VisibleForTesting
  static final List<Class<?>> getHardCodedClasses() {
    // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
    // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
    // https://sourceforge.net/p/proguard/bugs/418/
    try {
      return Collections.<Class<?>>singletonList(
          Class.forName("io.grpc.internal.PickFirstLoadBalancerProvider"));
    } catch (ClassNotFoundException e) {
      logger.log(Level.WARNING, "Unable to find pick-first LoadBalancer", e);
    }
    return Collections.emptyList();
  }

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int getPriority();

  /**
   * Returns the provider for the given load-balancing policy, or {@code null} if no suitable
   * provider can be found.  Each provider declares its policy name via {@link #getPolicyName}.
   */
  @Nullable
  public static LoadBalancerProvider getProvider(String policy) {
    return providers.get(policy);
  }

  /**
   * Returns the load-balancing policy name associated with this provider, which makes it selectable
   * via {@link #getProvider}.  This is called only when the class is loaded. It shouldn't change,
   * and there is no point doing so.
   */
  public abstract String getPolicyName();

  @Override
  public final String toString() {
    return MoreObjects.toStringHelper(this)
        .add("policy", getPolicyName())
        .add("priority", getPriority())
        .add("available", isAvailable())
        .toString();
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
