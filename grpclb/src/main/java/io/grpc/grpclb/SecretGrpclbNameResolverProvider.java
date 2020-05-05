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

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.internal.GrpcUtil;
import java.net.URI;

/**
 * A provider for {@code io.grpc.grpclb.GrpclbNameResolver}.
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
 */
// Make it package-private so that it cannot be directly referenced by users.  Java service loader
// requires the provider to be public, but we can hide it under a package-private class.
final class SecretGrpclbNameResolverProvider {

  private SecretGrpclbNameResolverProvider() {}

  public static final class Provider extends NameResolverProvider {

    private static final String SCHEME = "dns";

    @Override
    public GrpclbNameResolver newNameResolver(URI targetUri, Args args) {
      if (SCHEME.equals(targetUri.getScheme())) {
        String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
        Preconditions.checkArgument(
            targetPath.startsWith("/"),
            "the path component (%s) of the target (%s) must start with '/'",
            targetPath, targetUri);
        String name = targetPath.substring(1);
        return new GrpclbNameResolver(
            targetUri.getAuthority(),
            name,
            args,
            GrpcUtil.SHARED_CHANNEL_EXECUTOR,
            Stopwatch.createUnstarted(),
            InternalServiceProviders.isAndroid(getClass().getClassLoader()));
      } else {
        return null;
      }
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
    public int priority() {
      // Must be higher than DnsNameResolverProvider#priority.
      return 6;
    }
  }
}
