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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.Attributes;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolverProvider;
import io.grpc.internal.DnsNameResolver.ResourceResolverFactory;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A provider for {@link DnsNameResolver}.
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
public final class DnsNameResolverProvider extends NameResolverProvider {

  private static final Logger logger = Logger.getLogger(DnsNameResolverProvider.class.getName());

  private static final ResourceResolverFactory resourceResolverFactory =
      getResourceResolverFactory(DnsNameResolverProvider.class.getClassLoader());
  private static final String SCHEME = "dns";

  @Override
  public DnsNameResolver newNameResolver(URI targetUri, Attributes params) {
    if (SCHEME.equals(targetUri.getScheme())) {
      String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'", targetPath, targetUri);
      String name = targetPath.substring(1);
      return new DnsNameResolver(
          targetUri.getAuthority(),
          name,
          params,
          GrpcUtil.SHARED_CHANNEL_EXECUTOR,
          GrpcUtil.getDefaultProxyDetector(),
          Stopwatch.createUnstarted(),
          InternalServiceProviders.isAndroid(getClass().getClassLoader()),
          resourceResolverFactory);
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
  protected int priority() {
    return 5;
  }

  @Nullable
  @VisibleForTesting
  static ResourceResolverFactory getResourceResolverFactory(ClassLoader loader) {
    Class<? extends ResourceResolverFactory> jndiClazz;
    try {
      jndiClazz =
          Class.forName("io.grpc.internal.JndiResourceResolverFactory", true, loader)
              .asSubclass(ResourceResolverFactory.class);
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE, "Unable to find JndiResourceResolverFactory, skipping.", e);
      return null;
    }
    Constructor<? extends ResourceResolverFactory> jndiCtor;
    try {
      jndiCtor = jndiClazz.getConstructor();
    } catch (Exception e) {
      logger.log(Level.FINE, "Can't find JndiResourceResolverFactory ctor, skipping.", e);
      return null;
    }
    ResourceResolverFactory rrf;
    try {
      rrf = jndiCtor.newInstance();
    } catch (Exception e) {
      logger.log(Level.FINE, "Can't construct JndiResourceResolverFactory, skipping.", e);
      return null;
    }
    if (rrf.unavailabilityCause() != null) {
      logger.log(
          Level.FINE,
          "JndiResourceResolverFactory not available, skipping.",
          rrf.unavailabilityCause());
    }
    return rrf;
  }
}
