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

package io.grpc.xds;

import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import java.net.URI;

/** A deprecated provider for {@link XdsNameResolver}. */
// TODO(zdapeng): remove this class once it's not needed for interop testing.
@Deprecated
@Internal
public class XdsExperimentalNameResolverProvider extends NameResolverProvider {

  private final NameResolverProvider delegate = new XdsNameResolverProvider();

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    // Set priority value to be < 5 as we still want DNS resolver to be the primary default
    // resolver.
    return 4;
  }

  @Override
  public String getDefaultScheme() {
    return "xds-experimental";
  }

  @Override
  public NameResolver newNameResolver(URI targetUri, Args args) {
    return delegate.newNameResolver(targetUri, args);
  }
}
