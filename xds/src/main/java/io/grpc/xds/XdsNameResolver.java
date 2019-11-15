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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.JsonParser;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds-experimental" scheme.
 *
 * <p>The implementation is for load balancing alpha release only. No actual VHDS is involved. It
 * always returns a hard-coded service config that selects the xds_experimental LB policy with
 * round-robin as the child policy.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver extends NameResolver {

  // TODO(chengyuanzhang): delete this later, it was a workaround for demo.
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<Node> XDS_NODE = Attributes.Key.create("xds-node");
  // TODO(chengyuanzhang): delete this later, XdsClient (to be implemented) constructed here
  //  should take channel credentials config. This workaround is passing channel credentials
  //  config to xDS load balancer, which is doing the xDS RPC communication for now.
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<List<ChannelCreds>> XDS_CHANNEL_CREDS_LIST =
      Attributes.Key.create("xds-channel-creds-list");

  private final String authority;
  private final Bootstrapper bootstrapper;

  XdsNameResolver(String name) {
    this(name, Bootstrapper.getInstance());
  }

  @VisibleForTesting
  XdsNameResolver(String name, Bootstrapper bootstrapper) {
    URI nameUri = URI.create("//" + checkNotNull(name, "name"));
    Preconditions.checkArgument(nameUri.getHost() != null, "Invalid hostname: %s", name);
    authority =
        Preconditions.checkNotNull(
            nameUri.getAuthority(), "nameUri (%s) doesn't have an authority", nameUri);
    this.bootstrapper = Preconditions.checkNotNull(bootstrapper, "bootstrapper");
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

    String serviceConfig = "{"
        + "\"loadBalancingConfig\": ["
        + "{\"xds_experimental\" : {"
        + "\"balancerName\" : \"" + bootstrapInfo.getServerUri() + "\","
        + "\"childPolicy\" : [{\"round_robin\" : {}}]"
        + "}}"
        + "]}";
    Map<String, ?> config;
    try {
      config = (Map<String, ?>) JsonParser.parse(serviceConfig);
    } catch (IOException e) {
      listener.onError(
          Status.UNKNOWN.withDescription("Invalid service config").withCause(e));
      throw new AssertionError("Invalid service config");
    }
    Attributes attrs =
        Attributes.newBuilder()
            .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, config)
            .set(XDS_NODE, bootstrapInfo.getNode())
            .set(XDS_CHANNEL_CREDS_LIST, bootstrapInfo.getChannelCredentials())
            .build();
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build();
    listener.onResult(result);
  }

  @Override
  public void shutdown() {
  }
}
