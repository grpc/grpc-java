/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.internal.DnsNameResolverProvider;
import java.net.URI;
import javax.annotation.Nullable;

/**
 * Wrapper around a service provider.  This is used to hide compile time visibility to the name
 * resolver provider, which should only be created reflectively, and by
 * {@link NameResolverProvider}.
 *
 * @since 1.5.0
 */
final class SecretGrpclbDnsNameResolverProvider {

  /**
   * Exists outside of {@link GrpclbDnsNameResolverProvider} to avoid loading the class.
   */
  @VisibleForTesting
  static volatile boolean enableBalancerLookup = false;

  /**
   * This class delegates to normal DNS resolution, but allows it to also lookup balancer addresses
   * in the process.
   */
  public static final class GrpclbDnsNameResolverProvider extends NameResolverProvider {

    /**
     * Invoked reflectively by the service loader.
     */
    public GrpclbDnsNameResolverProvider() {}

    private final DnsNameResolverProvider delegate =
        new DnsNameResolverProvider(true /* balancer lookup */);

    @Override
    public boolean isAvailable() {
      // TODO(carl-mastrangelo): check if some env var is set as well.
      return delegate.isAvailable() && enableBalancerLookup;
    }

    @Override
    public int priority() {
      return delegate.priority() + 1;
    }

    @Override
    @Nullable
    public NameResolver newNameResolver(URI targetUri, Attributes params) {
      return delegate.newNameResolver(targetUri, params);
    }

    @Override
    public String getDefaultScheme() {
      return delegate.getDefaultScheme();
    }
  }
}
