/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.googleapis;

import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import java.net.URI;

/**
 * A provider for {@link GoogleCloudToProdNameResolver}, with experimental scheme.
 */
@Internal
public final class GoogleCloudToProdExperimentalNameResolverProvider extends NameResolverProvider {
  private final GoogleCloudToProdNameResolverProvider delegate =
      new GoogleCloudToProdNameResolverProvider("google-c2p-experimental");

  @Override
  public NameResolver newNameResolver(URI targetUri, Args args) {
    return delegate.newNameResolver(targetUri, args);
  }

  @Override
  public String getDefaultScheme() {
    return delegate.getDefaultScheme();
  }

  @Override
  protected boolean isAvailable() {
    return delegate.isAvailable();
  }

  @Override
  protected int priority() {
    return delegate.priority();
  }
}
