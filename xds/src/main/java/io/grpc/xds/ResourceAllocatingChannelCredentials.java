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

package io.grpc.xds;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.grpc.ChannelCredentials;
import io.grpc.internal.GrpcUtil;
import java.io.Closeable;

/**
 * {@code ChannelCredentials} which holds allocated resources (e.g. file watchers) upon
 * instantiation of a given {@code ChannelCredentials} object, which must be closed once
 * mentioned {@code ChannelCredentials} are no longer in use.
 */
public final class ResourceAllocatingChannelCredentials extends ChannelCredentials {
  public static ChannelCredentials create(
      ChannelCredentials channelCreds, Supplier<ImmutableList<Closeable>> resourcesSupplier) {
    return new ResourceAllocatingChannelCredentials(channelCreds, resourcesSupplier);
  }

  private final ChannelCredentials channelCreds;
  private final Supplier<ImmutableList<Closeable>> resourcesSupplier;
  private int refCount;
  private ImmutableList<Closeable> resourcesReleaser;

  private ResourceAllocatingChannelCredentials(
      ChannelCredentials channelCreds, Supplier<ImmutableList<Closeable>> resourcesSupplier) {
    this.channelCreds = Preconditions.checkNotNull(channelCreds, "channelCreds");
    this.resourcesSupplier = Preconditions.checkNotNull(resourcesSupplier, "resourcesSupplier");
    this.refCount = 0;
    this.resourcesReleaser = null;
  }

  public synchronized ChannelCredentials acquireChannelCredentials() {
    if (refCount++ == 0) {
      resourcesReleaser = resourcesSupplier.get();
    }
    return channelCreds;
  }

  public synchronized void releaseChannelCredentials() {
    if (--refCount == 0) {
      for (Closeable resource : resourcesReleaser) {
        GrpcUtil.closeQuietly(resource);
      }
      resourcesReleaser = null;
    }
    Preconditions.checkState(
        refCount >= 0, "Channel credentials were released more times than they were acquired");
  }

  /**
   * Please use {@link #acquireChannelCredentials()} to get a shared instance of
   * {@code ChannelCredentials} for which stripped tokens can be obtained.
   */
  @Override
  public ChannelCredentials withoutBearerTokens() {
    throw new UnsupportedOperationException("Cannot get stripped tokens");
  }
}
