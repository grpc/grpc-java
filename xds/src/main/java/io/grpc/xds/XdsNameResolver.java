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

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds-experimental" scheme.
 *
 * <p>Resolving a gRPC target involves contacting the control plane management server via xDS
 * protocol to retrieve service information and produce a service config to the caller.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver extends NameResolver {

  private final XdsLogger logger;
  private final String authority;
  private final XdsChannelFactory channelFactory;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final ServiceConfigParser serviceConfigParser;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Bootstrapper bootstrapper;

  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;

  XdsNameResolver(
      String name,
      Args args,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      XdsChannelFactory channelFactory,
      Bootstrapper bootstrapper) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    this.syncContext = checkNotNull(args.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(args.getScheduledExecutorService(), "timeService");
    this.serviceConfigParser = checkNotNull(args.getServiceConfigParser(), "serviceConfigParser");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
    logger = XdsLogger.withLogId(InternalLogId.allocate("xds-resolver", name));
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void start(final Listener2 listener) {
    BootstrapInfo bootstrapInfo = null;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
    } catch (Exception e) {
      listener.onError(Status.UNAVAILABLE.withDescription("Failed to bootstrap").withCause(e));
      return;
    }
    final List<ServerInfo> serverList = bootstrapInfo.getServers();
    final Node node = bootstrapInfo.getNode();
    if (serverList.isEmpty()) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("No management server provided by bootstrap"));
      return;
    }

    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return
            new XdsClientImpl(
                authority,
                serverList,
                channelFactory,
                node,
                syncContext,
                timeService,
                backoffPolicyProvider,
                stopwatchSupplier);
      }
    };
    xdsClientPool = new RefCountedXdsClientObjectPool(xdsClientFactory);
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchConfigData(authority, new ConfigWatcher() {
      @Override
      public void onConfigChanged(ConfigUpdate update) {
        logger.log(
            XdsLogLevel.INFO,
            "Received config update from xDS client {0}: cluster_name={1}",
            xdsClient, update.getClusterName());
        String serviceConfig = "{\n"
            + "  \"loadBalancingConfig\": [\n"
            + "    {\n"
            + "      \"cds_experimental\": {\n"
            + "        \"cluster\": \"" + update.getClusterName() + "\"\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        Map<String, ?> config;
        try {
          config = (Map<String, ?>) JsonParser.parse(serviceConfig);
        } catch (IOException e) {
          listener.onError(
              Status.UNKNOWN.withDescription("Invalid service config").withCause(e));
          return;
        }
        logger.log(XdsLogLevel.INFO, "Generated service config:\n{0}", serviceConfig);
        Attributes attrs =
            Attributes.newBuilder()
                .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, config)
                .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                .build();
        ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(config);
        ResolutionResult result =
            ResolutionResult.newBuilder()
                .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
                .setAttributes(attrs)
                .setServiceConfig(parsedServiceConfig)
                .build();
        listener.onResult(result);
      }

      @Override
      public void onError(Status error) {
        // In order to distinguish between IO error and resource not found, which trigger
        // different handling, return an empty resolution result to channel for resource not
        // found.
        // TODO(chengyuanzhang): Returning an empty resolution result based on status code is
        //  a temporary solution. More design discussion needs to be done.
        if (error.getCode().equals(Code.NOT_FOUND)) {
          logger.log(
              XdsLogLevel.WARNING,
              "Received error from xDS client {0}: {1}", xdsClient, error.getDescription());
          listener.onResult(ResolutionResult.newBuilder().build());
          return;
        }
        listener.onError(Status.UNAVAILABLE.withDescription(error.getDescription()));
      }
    });
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }
}
