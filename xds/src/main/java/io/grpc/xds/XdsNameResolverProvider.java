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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.grpc.Internal;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.internal.ObjectPool;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * A provider for {@link XdsNameResolver}.
 *
 * <p>It resolves a target URI whose scheme is {@code "xds"}. The authority of the
 * target URI is never used for current release. The path of the target URI, excluding the leading
 * slash {@code '/'}, will indicate the name to use in the VHDS query.
 *
 * <p>This class should not be directly referenced in code. The resolver should be accessed
 * through {@link io.grpc.NameResolverRegistry} with the URI scheme "xds".
 */
@Internal
public final class XdsNameResolverProvider extends NameResolverProvider {

  private static final String SCHEME = "xds";
  private final String scheme;
  private final Map<String, ?> bootstrapOverride;

  public XdsNameResolverProvider() {
    this(SCHEME, null);
  }

  private XdsNameResolverProvider(String scheme,
                                 @Nullable Map<String, ?> bootstrapOverride) {
    this.scheme = checkNotNull(scheme, "scheme");
    this.bootstrapOverride = bootstrapOverride;
  }

  /**
   * A convenient method to allow creating a {@link XdsNameResolverProvider} with custom scheme
   * and bootstrap.
   */
  public static XdsNameResolverProvider createForTest(String scheme,
                                                      @Nullable Map<String, ?> bootstrapOverride) {
    return new XdsNameResolverProvider(scheme, bootstrapOverride);
  }

  @Override
  public XdsNameResolver newNameResolver(URI targetUri, Args args) {
    if (scheme.equals(targetUri.getScheme())) {
      String targetPath = checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(
          targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'",
          targetPath,
          targetUri);
      String name = targetPath.substring(1);
      return new XdsNameResolver(
          targetUri.getAuthority(), name, args.getOverrideAuthority(),
          args.getServiceConfigParser(), args.getSynchronizationContext(),
          args.getScheduledExecutorService(),
          bootstrapOverride);
    }
    return null;
  }

  @Override
  public String getDefaultScheme() {
    return scheme;
  }

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
  protected Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return Collections.singleton(InetSocketAddress.class);
  }

  interface XdsClientPoolFactory {
    void setBootstrapOverride(Map<String, ?> bootstrap);

    @Nullable
    ObjectPool<XdsClient> get();

    ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException;
  }

  /**
   * Provides the counter for aggregating outstanding requests per cluster:eds_service_name.
   */
  interface CallCounterProvider {
    AtomicLong getOrCreate(String cluster, @Nullable String edsServiceName);
  }
}
