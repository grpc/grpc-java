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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.Status;
import javax.annotation.Nullable;

/**
 * {@link ServiceConfigState} holds the state of the current service config.  It must be mutated
 * and read from {@link ManagedChannelImpl} constructor or the provided syncContext.
 */
final class ServiceConfigState {
  @Nullable private final ManagedChannelServiceConfig defaultServiceConfig;
  private final boolean lookUpServiceConfig;

  // mutable state
  @Nullable private ManagedChannelServiceConfig currentServiceConfig;
  @Nullable private Status currentError;
  // Has there been at least one successful update?
  private boolean updated;

  /**
   * @param defaultServiceConfig The initial service config, or {@code null} if absent.
   * @param lookUpServiceConfig {@code true} if service config updates might occur.
   * @param syncCtx The synchronization context that this is accessed from.
   */
  ServiceConfigState(
      @Nullable ManagedChannelServiceConfig defaultServiceConfig,
      boolean lookUpServiceConfig) {
    this.defaultServiceConfig = defaultServiceConfig;
    this.lookUpServiceConfig = lookUpServiceConfig;
    if (!lookUpServiceConfig) {
      this.currentServiceConfig = defaultServiceConfig;
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
   * Gets the current service config.
   *
   * @throws IllegalStateException if the service config has not yet been updated.
   */
  @Nullable ManagedChannelServiceConfig getCurrentServiceConfig() {
    checkState(!shouldWaitOnServiceConfig(), "still waiting on service config");
    return currentServiceConfig;
  }

  /**
   * Gets the current service config error.
   *
   * @throws IllegalStateException if the service config has not yet been updated.
   */
  @Nullable Status getCurrentError() {
    checkState(!shouldWaitOnServiceConfig(), "still waiting on service config");
    return currentError;
  }

  /**
   * Returns {@code true} if the update was successfully applied, else {@code false}.
   */
  boolean update(Status error) {
    checkNotNull(error, "error");
    checkArgument(!error.isOk(), "can't use OK error");
    checkState(expectUpdates(), "unexpected service config update");
    boolean firstUpdate = !updated;
    updated = true;
    if ((firstUpdate && defaultServiceConfig == null) || currentError != null) {
      assert currentServiceConfig == null;
      currentError = error;
      return true;
    }
    if (currentServiceConfig == null) {
      currentServiceConfig = defaultServiceConfig;
    }
    return false;
  }

  /**
   * Returns {@code} if the update was successfully applied, else {@code false}.
   */
  void update(@Nullable ManagedChannelServiceConfig newServiceConfig) {
    checkState(expectUpdates(), "unexpected service config update");
    updated = true;
    if (newServiceConfig != null) {
      currentServiceConfig = newServiceConfig;
    } else {
      currentServiceConfig = defaultServiceConfig;
    }
    currentError = null;
  }

  boolean expectUpdates() {
    return lookUpServiceConfig;
  }
}
