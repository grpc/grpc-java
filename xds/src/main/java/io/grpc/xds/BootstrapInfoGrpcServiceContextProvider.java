/*
 * Copyright 2026 The gRPC Authors
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

import io.grpc.NameResolverRegistry;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.internal.grpcservice.AllowedGrpcService;
import io.grpc.xds.internal.grpcservice.AllowedGrpcServices;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContext;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Concrete implementation of {@link GrpcServiceXdsContextProvider} that uses
 * {@link BootstrapInfo} data to resolve context.
 */
final class BootstrapInfoGrpcServiceContextProvider
    implements GrpcServiceXdsContextProvider {

  private final boolean isTrustedControlPlane;
  private final AllowedGrpcServices allowedGrpcServices;
  private final NameResolverRegistry nameResolverRegistry;

  BootstrapInfoGrpcServiceContextProvider(BootstrapInfo bootstrapInfo, ServerInfo serverInfo) {
    this.isTrustedControlPlane = serverInfo.isTrustedXdsServer();
    this.allowedGrpcServices = bootstrapInfo.allowedGrpcServices()
        .filter(AllowedGrpcServices.class::isInstance)
        .map(AllowedGrpcServices.class::cast)
        .orElse(AllowedGrpcServices.empty());
    this.nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();
  }

  @Override
  public GrpcServiceXdsContext getContextForTarget(String targetUri) {
    Optional<AllowedGrpcService> validAllowedGrpcService =
        Optional.ofNullable(allowedGrpcServices.services().get(targetUri));

    boolean isTargetUriSchemeSupported = false;
    try {
      URI uri = new URI(targetUri);
      String scheme = uri.getScheme();
      if (scheme != null) {
        isTargetUriSchemeSupported =
            nameResolverRegistry.getProviderForScheme(scheme) != null;
      }
    } catch (URISyntaxException e) {
      // Fallback or ignore if not a valid URI
    }

    return GrpcServiceXdsContext.create(
        isTrustedControlPlane,
        validAllowedGrpcService,
        isTargetUriSchemeSupported
    );
  }
}
