/*
 * Copyright 2015 The gRPC Authors
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

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Uri;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A provider for {@link DnsNameResolver}.
 *
 * <p>It resolves a target URI whose scheme is {@code "dns"}. The (optional) authority of the target
 * URI is reserved for the address of alternative DNS server (not implemented yet). The target URI
 * must be hierarchical and have exactly one path segment which will be interpreted as an RFC 2396
 * "server-based" authority and used as the "service authority" of the resulting {@link
 * NameResolver}. The "host" part of this authority is the name to be resolved by DNS. The "port"
 * part of this authority (if present) will become the port number for all {@link InetSocketAddress}
 * produced by this resolver. For example:
 *
 * <ul>
 *   <li>{@code "dns:///foo.googleapis.com:8080"} (using default DNS)</li>
 *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"} (using alternative DNS (not implemented
 *   yet))</li>
 *   <li>{@code "dns:///foo.googleapis.com"} (output addresses will have port {@link
 *       NameResolver.Args#getDefaultPort()})</li>
 * </ul>
 */
public final class DnsNameResolverProvider extends NameResolverProvider {

  private static final String SCHEME = "dns";

  private static final boolean IS_ANDROID = InternalServiceProviders
      .isAndroid(DnsNameResolverProvider.class.getClassLoader());

  @Override
  public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    // TODO(jdcormie): Remove once RFC 3986 migration is complete.
    if (SCHEME.equals(targetUri.getScheme())) {
      String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'", targetPath, targetUri);
      String name = targetPath.substring(1);
      return new DnsNameResolver(
          targetUri.getAuthority(),
          name,
          args,
          GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          Stopwatch.createUnstarted(),
          IS_ANDROID);
    } else {
      return null;
    }
  }

  @Override
  public NameResolver newNameResolver(Uri targetUri, final NameResolver.Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      List<String> pathSegments = targetUri.getPathSegments();
      Preconditions.checkArgument(!pathSegments.isEmpty(),
          "expected 1 path segment in target %s but found %s", targetUri, pathSegments);
      String domainNameToResolve = pathSegments.get(0);
      return new DnsNameResolver(
          targetUri.getAuthority(),
          domainNameToResolve,
          args,
          GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          Stopwatch.createUnstarted(),
          IS_ANDROID);
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
    return 5;
  }

  @Override
  public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return Collections.singleton(InetSocketAddress.class);
  }
}
