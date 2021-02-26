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

package io.grpc.xds;

import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.internal.GrpcUtil;
import java.net.URI;

/**
 * A provider for {@link GoogleCloudToProdNameResolver}.
 */
@Internal
public final class GoogleCloudToProdNameResolverProvider extends NameResolverProvider {

  private static final boolean enableC2PResolver =
      Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_GOOGLE_C2P_RESOLVER"));
  private static final String SCHEME = "google-c2p";

  @Override
  public NameResolver newNameResolver(URI targetUri, Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      return new GoogleCloudToProdNameResolver(
          targetUri, args, GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          SharedXdsClientPoolProvider.getDefaultProvider());
    }
    return null;
  }

  @Override
  public String getDefaultScheme() {
    return SCHEME;
  }

  @Override
  protected boolean isAvailable() {
    if (enableC2PResolver) {
      return true;
    }
    return false;
  }

  @Override
  protected int priority() {
    return 4;
  }
}
