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

package io.grpc.netty;

import com.google.common.base.Preconditions;
import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.netty.channel.unix.DomainSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;

@Internal
public final class UdsNameResolverProvider extends NameResolverProvider {

  private static final String SCHEME = "unix";

  @Override
  public UdsNameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      return new UdsNameResolver(targetUri.getAuthority(), getTargetPathFromUri(targetUri));
    } else {
      return null;
    }
  }

  static String getTargetPathFromUri(URI targetUri) {
    Preconditions.checkArgument(SCHEME.equals(targetUri.getScheme()), "scheme must be " + SCHEME);
    String targetPath = targetUri.getPath();
    if (targetPath == null) {
      targetPath = Preconditions.checkNotNull(targetUri.getSchemeSpecificPart(), "targetPath");
    }
    return targetPath;
  }

  @Override
  public String getDefaultScheme() {
    return SCHEME;
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 3;
  }

  @Override
  protected Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return Collections.singleton(DomainSocketAddress.class);
  }
}
