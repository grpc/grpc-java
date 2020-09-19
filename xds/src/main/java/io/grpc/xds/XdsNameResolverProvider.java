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
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.Internal;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClient.XdsClientFactory;
import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;

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

  @Override
  public XdsNameResolver newNameResolver(URI targetUri, Args args) {
    if (SCHEME.equals(targetUri.getScheme())) {
      String targetPath = checkNotNull(targetUri.getPath(), "targetPath");
      Preconditions.checkArgument(
          targetPath.startsWith("/"),
          "the path component (%s) of the target (%s) must start with '/'",
          targetPath,
          targetUri);
      String name = targetPath.substring(1);
      XdsClientPoolFactory xdsClientPoolFactory =
          new RefCountedXdsClientPoolFactory(
              name, args.getSynchronizationContext(), args.getScheduledExecutorService(),
              new ExponentialBackoffPolicy.Provider(), GrpcUtil.STOPWATCH_SUPPLIER);
      return new XdsNameResolver(
          name, args.getServiceConfigParser(),
          args.getSynchronizationContext(), xdsClientPoolFactory);
    }
    return null;
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
    // Set priority value to be < 5 as we still want DNS resolver to be the primary default
    // resolver.
    return 4;
  }

  static class RefCountedXdsClientPoolFactory implements XdsClientPoolFactory {
    private final String serviceName;
    private final SynchronizationContext syncContext;
    private final ScheduledExecutorService timeService;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Supplier<Stopwatch> stopwatchSupplier;

    RefCountedXdsClientPoolFactory(
        String serviceName,
        SynchronizationContext syncContext,
        ScheduledExecutorService timeService,
        BackoffPolicy.Provider backoffPolicyProvider,
        Supplier<Stopwatch> stopwatchSupplier) {
      this.serviceName = checkNotNull(serviceName, "serviceName");
      this.syncContext = checkNotNull(syncContext, "syncContext");
      this.timeService = checkNotNull(timeService, "timeService");
      this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
      this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    }

    @Override
    public ObjectPool<XdsClient> newXdsClientObjectPool(
        final BootstrapInfo bootstrapInfo, final XdsChannel channel) {
      XdsClientFactory xdsClientFactory = new XdsClientFactory() {
        @Override
        XdsClient createXdsClient() {
          return new XdsClientImpl(
              serviceName, channel, bootstrapInfo.getNode(), syncContext, timeService,
              backoffPolicyProvider, stopwatchSupplier);
        }
      };
      return new RefCountedXdsClientObjectPool(xdsClientFactory);
    }
  }

  interface XdsClientPoolFactory {
    ObjectPool<XdsClient> newXdsClientObjectPool(BootstrapInfo bootstrapInfo, XdsChannel channel);
  }
}
