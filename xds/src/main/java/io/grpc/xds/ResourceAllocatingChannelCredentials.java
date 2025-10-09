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
import com.google.common.collect.ImmutableList;
import io.grpc.ChannelCredentials;
import java.io.Closeable;

/**
 * {@code ChannelCredentials} which holds allocated resources (e.g. file watchers) upon
 * instantiation of a given {@code ChannelCredentials} object, which must be closed once
 * mentioned {@code ChannelCredentials} are no longer in use.
 */
public final class ResourceAllocatingChannelCredentials extends ChannelCredentials {
  public static ChannelCredentials create(
      ChannelCredentials channelCreds, ImmutableList<Closeable> resources) {
    return new ResourceAllocatingChannelCredentials(channelCreds, resources);
  }

  private final ChannelCredentials channelCreds;
  private final ImmutableList<Closeable> resources;

  private ResourceAllocatingChannelCredentials(
      ChannelCredentials channelCreds, ImmutableList<Closeable> resources) {
    this.channelCreds = Preconditions.checkNotNull(channelCreds, "channelCreds");
    this.resources = Preconditions.checkNotNull(resources, "resources");
  }

  public ChannelCredentials getChannelCredentials() {
    return channelCreds;
  }

  public ImmutableList<Closeable> getAllocatedResources() {
    return resources;
  }

  @Override
  public ChannelCredentials withoutBearerTokens() {
    return channelCreds.withoutBearerTokens();
  }
}
