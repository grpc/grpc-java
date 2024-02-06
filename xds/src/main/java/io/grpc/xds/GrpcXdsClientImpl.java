/*
 * Copyright 2024 The gRPC Authors
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

import static io.grpc.xds.client.Bootstrapper.XDSTP_SCHEME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.LoadReportClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.client.XdsTransportFactory;
import io.grpc.xds.internal.security.TlsContextManagerImpl;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

public class GrpcXdsClientImpl extends XdsClientImpl {
  private final boolean isFederationEnabled;

  public GrpcXdsClientImpl(XdsTransportFactory xdsTransportFactory,
                           Bootstrapper.BootstrapInfo bootstrapInfo,
                           boolean isFederationEnabled,
                           ScheduledExecutorService timeService,
                           BackoffPolicy.Provider backoffPolicyProvider,
                           Supplier<Stopwatch> stopwatchSupplier, TimeProvider timeProvider) {
    super(xdsTransportFactory, bootstrapInfo, timeService,
        backoffPolicyProvider, stopwatchSupplier, timeProvider, MessagePrinter.INSTANCE,
        new TlsContextManagerImpl(bootstrapInfo));
    this.isFederationEnabled = isFederationEnabled;
  }

  @VisibleForTesting
  @Override
  public Map<Bootstrapper.ServerInfo, LoadReportClient> getServerLrsClientMap() {
    return ImmutableMap.copyOf(serverLrsClientMap);
  }

  @Override
  @Nullable
  public Set<String> getResourceNamesToParse(XdsResourceType<?> xdsResourceType) {
    Set<String> toParseResourceNames = null;
    if (!(xdsResourceType == XdsListenerResource.getInstance()
        || xdsResourceType == XdsRouteConfigureResource.getInstance())) {
      toParseResourceNames = getResourceKeys(xdsResourceType);
    }
    return toParseResourceNames;
  }

  @Override
  protected Bootstrapper.ServerInfo getServerInfo(String resource) {
    if (isFederationEnabled && resource.startsWith(XDSTP_SCHEME)) {
      URI uri = URI.create(resource);
      String authority = uri.getAuthority();
      if (authority == null) {
        authority = "";
      }
      Bootstrapper.AuthorityInfo authorityInfo = bootstrapInfo.authorities().get(authority);
      if (authorityInfo == null || authorityInfo.xdsServers().isEmpty()) {
        return null;
      }
      return authorityInfo.xdsServers().get(0);
    }
    return bootstrapInfo.servers().get(0); // use first server
  }
}