/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.internal.testing;

import com.google.common.collect.ImmutableList;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import java.net.SocketAddress;
import java.net.URI;

/** A name resolver to always resolve the given URI into the given address. */
public final class FakeNameResolverProvider extends NameResolverProvider {

  private final URI targetUri;
  private final SocketAddress address;

  public FakeNameResolverProvider(String targetUri, SocketAddress address) {
    this.targetUri = URI.create(targetUri);
    this.address = address;
  }

  @Override
  public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    if (targetUri.equals(this.targetUri)) {
      return new FakeNameResolver(address);
    }
    return null;
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 5; // Default
  }

  @Override
  public String getDefaultScheme() {
    return targetUri.getScheme();
  }

  /** A single name resolver. */
  private static final class FakeNameResolver extends NameResolver {
    private static final String AUTHORITY = "fake-authority";

    private final SocketAddress address;
    private volatile boolean shutdown;

    private FakeNameResolver(SocketAddress address) {
      this.address = address;
    }

    @Override
    public void start(Listener2 listener) {
      if (shutdown) {
        listener.onError(Status.FAILED_PRECONDITION.withDescription("Resolver is shutdown"));
      } else {
        listener.onResult(
            ResolutionResult.newBuilder()
                .setAddresses(ImmutableList.of(new EquivalentAddressGroup(address)))
                .build());
      }
    }

    @Override
    public String getServiceAuthority() {
      return AUTHORITY;
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }
  }
}
