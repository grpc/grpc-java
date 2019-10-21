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

package io.grpc.grpclb;

import io.grpc.internal.BaseDnsNameResolverProvider;

/**
 * A provider for {@code io.grpc.internal.DnsNameResolver} for gRPC lb.
 *
 * <p>It resolves a target URI whose scheme is {@code "dns"}. The (optional) authority of the target
 * URI is reserved for the address of alternative DNS server (not implemented yet). The path of the
 * target URI, excluding the leading slash {@code '/'}, is treated as the host name and the optional
 * port to be resolved by DNS. Example target URIs:
 *
 * <ul>
 *   <li>{@code "dns:///foo.googleapis.com:8080"} (using default DNS)</li>
 *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"} (using alternative DNS (not implemented
 *   yet))</li>
 *   <li>{@code "dns:///foo.googleapis.com"} (without port)</li>
 * </ul>
 *
 * <p>Note: the main difference between {@code io.grpc.DnsNameResolver} is service record is enabled
 * by default.
 */
// Make it package-private so that it cannot be directly referenced by users.  Java service loader
// requires the provider to be public, but we can hide it under a package-private class.
final class SecretGrpclbNameResolverProvider {

  private SecretGrpclbNameResolverProvider() {}

  public static final class Provider extends BaseDnsNameResolverProvider {

    private static final boolean SRV_ENABLED =
        Boolean.parseBoolean(System.getProperty(ENABLE_GRPCLB_PROPERTY_NAME, "true"));

    @Override
    protected boolean isSrvEnabled() {
      return SRV_ENABLED;
    }

    @Override
    public int priority() {
      // Must be higher than DnsNameResolverProvider#priority.
      return 6;
    }
  }
}
