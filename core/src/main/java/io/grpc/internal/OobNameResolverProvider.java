/*
 * Copyright 2025 The gRPC Authors
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

import static java.util.Objects.requireNonNull;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * A provider that is passed addresses and relays those addresses to its created resolvers.
 */
final class OobNameResolverProvider extends NameResolverProvider {
  private final String authority;
  private final SynchronizationContext parentSyncContext;
  // Only accessed from parentSyncContext
  @SuppressWarnings("JdkObsolete") // LinkedList uses O(n) memory, including after deletions
  private final Collection<OobNameResolver> resolvers = new LinkedList<>();
  // Only accessed from parentSyncContext
  private List<EquivalentAddressGroup> lastEags;

  public OobNameResolverProvider(
      String authority, List<EquivalentAddressGroup> eags, SynchronizationContext syncContext) {
    this.authority = requireNonNull(authority, "authority");
    this.lastEags = requireNonNull(eags, "eags");
    this.parentSyncContext = requireNonNull(syncContext, "syncContext");
  }

  @Override
  public String getDefaultScheme() {
    return "oob";
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 5; // Doesn't matter, as we expect only one provider in the registry
  }

  public void updateAddresses(List<EquivalentAddressGroup> eags) {
    requireNonNull(eags, "eags");
    parentSyncContext.execute(() -> {
      this.lastEags = eags;
      for (OobNameResolver resolver : resolvers) {
        resolver.updateAddresses(eags);
      }
    });
  }

  @Override
  public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    return new OobNameResolver(args.getSynchronizationContext());
  }

  final class OobNameResolver extends NameResolver {
    private final SynchronizationContext syncContext;
    // Null before started, and after shutdown. Only accessed from syncContext
    private Listener2 listener;

    public OobNameResolver(SynchronizationContext syncContext) {
      this.syncContext = requireNonNull(syncContext, "syncContext");
    }

    @Override
    public String getServiceAuthority() {
      return authority;
    }

    @Override
    public void start(Listener2 listener) {
      this.listener = requireNonNull(listener, "listener");
      parentSyncContext.execute(() -> {
        resolvers.add(this);
        updateAddresses(lastEags);
      });
    }

    void updateAddresses(List<EquivalentAddressGroup> eags) {
      parentSyncContext.throwIfNotInThisSynchronizationContext();
      syncContext.execute(() -> {
        if (listener == null) {
          return;
        }
        listener.onResult2(ResolutionResult.newBuilder()
            .setAddressesOrError(StatusOr.fromValue(lastEags))
            .build());
      });
    }

    @Override
    public void shutdown() {
      this.listener = null;
      parentSyncContext.execute(() -> resolvers.remove(this));
    }
  }
}
