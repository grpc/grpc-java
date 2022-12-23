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

package io.grpc.binder;

import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/**
 * A security policy for a gRPC server.
 *
 * Contains a default policy, and optional policies for each server.
 */
public final class ServerSecurityPolicy {

  private final SecurityPolicy defaultPolicy;
  private final ImmutableMap<String, SecurityPolicy> perServicePolicies;

  ServerSecurityPolicy() {
    this(ImmutableMap.of());
  }

  private ServerSecurityPolicy(ImmutableMap<String, SecurityPolicy> perServicePolicies) {
    this.defaultPolicy = SecurityPolicies.internalOnly();
    this.perServicePolicies = perServicePolicies;
  }

  /**
   * Return whether the given Android UID is authorized to access a particular service.
   *
   * <b>IMPORTANT</b>: This method may block for extended periods of time.
   *
   * @param uid The Android UID to authenticate.
   * @param serviceName The name of the gRPC service being called.
   */
  @CheckReturnValue
  public Status checkAuthorizationForService(int uid, String serviceName) {
    return perServicePolicies.getOrDefault(serviceName, defaultPolicy).checkAuthorization(uid);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for an AndroidServiceSecurityPolicy. */
  public static final class Builder {
    private final Map<String, SecurityPolicy> grpcServicePolicies;

    private Builder() {
      grpcServicePolicies = new HashMap<>();
    }

    /**
     * Specify a policy specific to a particular gRPC service.
     *
     * @param serviceName The fully qualified name of the gRPC service (from the proto).
     * @param policy The security policy to apply to the service.
     */
    public Builder servicePolicy(String serviceName, SecurityPolicy policy) {
      grpcServicePolicies.put(serviceName, policy);
      return this;
    }

    public ServerSecurityPolicy build() {
      return new ServerSecurityPolicy(ImmutableMap.copyOf(grpcServicePolicies));
    }
  }
}
