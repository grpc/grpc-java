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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;

import io.grpc.NameResolver.ConfigOrError;
import javax.annotation.Nullable;

/**
 * {@link ServiceConfigState} holds the state of the current service config.  It must be mutated
 * and read from {@link ManagedChannelImpl} constructor or the provided syncContext.
 */
final class ServiceConfigState {
  @Nullable private final ConfigOrError defaultServiceConfig;
  private final boolean lookUpServiceConfig;

  // mutable state
  @Nullable private ConfigOrError currentServiceConfigOrError;
  // Has there been at least one update?
  private boolean updated;

  /**
   * @param defaultServiceConfig The initial service config, or {@code null} if absent.
   * @param lookUpServiceConfig {@code true} if service config updates might occur.
   * @param syncCtx The synchronization context that this is accessed from.
   */
  ServiceConfigState(
      @Nullable ManagedChannelServiceConfig defaultServiceConfig,
      boolean lookUpServiceConfig) {
    if (defaultServiceConfig == null) {
      this.defaultServiceConfig = null;
    } else {
      this.defaultServiceConfig = ConfigOrError.fromConfig(defaultServiceConfig);
    }
    this.lookUpServiceConfig = lookUpServiceConfig;
    if (!lookUpServiceConfig) {
      this.currentServiceConfigOrError = this.defaultServiceConfig;
    }
  }

  /**
   * Returns {@code true} if it RPCs should wait on a service config resolution.  This can return
   * {@code false} if:
   *
   * <ul>
   *   <li>There is a valid service config from the name resolver
   *   <li>There is a valid default service config and a service config error from the name
   *       resolver
   *   <li>No service config from the name resolver, and no intent to lookup a service config.
   * </ul>
   *
   * <p>In the final case, the default service config may be present or absent, and will be the
   * current service config.
   */
  boolean shouldWaitOnServiceConfig() {
    return !(updated || !expectUpdates());
  }

  /**
   * Gets the current service config or error.
   *
   * @throws IllegalStateException if the service config has not yet been updated.
   */
  @Nullable
  ConfigOrError getCurrent() {
    checkState(!shouldWaitOnServiceConfig(), "still waiting on service config");
    return currentServiceConfigOrError;
  }

  void update(@Nullable ConfigOrError coe) {
    checkState(expectUpdates(), "unexpected service config update");
    boolean firstUpdate = !updated;
    updated = true;
    if (firstUpdate) {
      if (coe == null) {
        currentServiceConfigOrError = defaultServiceConfig;
      } else if (coe.getError() != null) {
        if (defaultServiceConfig != null) {
          currentServiceConfigOrError = defaultServiceConfig;
        } else {
          currentServiceConfigOrError = coe;
        }
      } else {
        assert coe.getConfig() != null;
        currentServiceConfigOrError = coe;
      }
    } else {
      if (coe == null) {
        if (defaultServiceConfig != null) {
          currentServiceConfigOrError = defaultServiceConfig;
        } else {
          currentServiceConfigOrError = null;
        }
      } else if (coe.getError() != null) {
        if (currentServiceConfigOrError != null && currentServiceConfigOrError.getError() != null) {
          currentServiceConfigOrError = coe;
        } else {
          // discard
        }
      } else {
        assert coe.getConfig() != null;
        currentServiceConfigOrError = coe;
      }
    }
  }

  boolean expectUpdates() {
    return lookUpServiceConfig;
  }
}
