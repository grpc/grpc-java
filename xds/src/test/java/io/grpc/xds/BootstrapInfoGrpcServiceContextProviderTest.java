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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.EnvoyProtoData;
import io.grpc.xds.internal.grpcservice.AllowedGrpcService;
import io.grpc.xds.internal.grpcservice.AllowedGrpcServices;
import io.grpc.xds.internal.grpcservice.ChannelCredsConfig;
import io.grpc.xds.internal.grpcservice.ConfiguredChannelCredentials;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link BootstrapInfoGrpcServiceContextProvider}.
 */
@RunWith(JUnit4.class)
public class BootstrapInfoGrpcServiceContextProviderTest {

  private static final ChannelCredentials CREDENTIALS = InsecureChannelCredentials.create();
  private static final ChannelCredsConfig DUMMY_CONFIG = () -> "dummy";
  private static final EnvoyProtoData.Node DUMMY_NODE =
      EnvoyProtoData.Node.newBuilder().setId("node-id").build();

  private static final BootstrapInfo DUMMY_BOOTSTRAP = BootstrapInfo.builder()
      .servers(ImmutableList.of())
      .node(DUMMY_NODE)
      .build();

  private static ServerInfo createServerInfo(boolean isTrusted) {
    return ServerInfo.create("xds:///any", CREDENTIALS, false, isTrusted, false, false);
  }

  @Test
  public void getContextForTarget_trustedServer() {
    ServerInfo serverInfo = createServerInfo(true);
    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(DUMMY_BOOTSTRAP, serverInfo);

    GrpcServiceXdsContext context = provider.getContextForTarget("xds:///any");
    assertThat(context.isTrustedControlPlane()).isTrue();
  }

  @Test
  public void getContextForTarget_untrustedServer() {
    ServerInfo serverInfo = createServerInfo(false);
    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(DUMMY_BOOTSTRAP, serverInfo);

    GrpcServiceXdsContext context = provider.getContextForTarget("xds:///any");
    assertThat(context.isTrustedControlPlane()).isFalse();
  }

  @Test
  public void getContextForTarget_allowedGrpcServices() {
    ConfiguredChannelCredentials creds = ConfiguredChannelCredentials.create(
        CREDENTIALS, DUMMY_CONFIG);
    AllowedGrpcService allowedService = AllowedGrpcService.builder()
        .configuredChannelCredentials(creds)
        .build();

    Map<String, AllowedGrpcService> servicesMap = new HashMap<>();
    servicesMap.put("xds:///target1", allowedService);
    AllowedGrpcServices allowedGrpcServices = AllowedGrpcServices.create(servicesMap);

    BootstrapInfo bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of())
        .node(DUMMY_NODE)
        .allowedGrpcServices(Optional.of(allowedGrpcServices))
        .build();

    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(bootstrapInfo, createServerInfo(false));

    GrpcServiceXdsContext context = provider.getContextForTarget("xds:///target1");
    assertThat(context.validAllowedGrpcService().isPresent()).isTrue();
    assertThat(context.validAllowedGrpcService().get()).isEqualTo(allowedService);

    // Target not in map
    GrpcServiceXdsContext context2 = provider.getContextForTarget("xds:///target2");
    assertThat(context2.validAllowedGrpcService().isPresent()).isFalse();
  }

  @Test
  public void getContextForTarget_schemeSupported() {
    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(DUMMY_BOOTSTRAP, createServerInfo(false));

    assertThat(provider.getContextForTarget("dns:///foo").isTargetUriSchemeSupported()).isTrue();
    assertThat(provider.getContextForTarget("unknown:///foo").isTargetUriSchemeSupported())
        .isFalse();
  }

  @Test
  public void getContextForTarget_invalidUri() {
    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(DUMMY_BOOTSTRAP, createServerInfo(false));

    GrpcServiceXdsContext context = provider.getContextForTarget("invalid:uri:with:colons");
    assertThat(context.isTargetUriSchemeSupported()).isFalse();
  }

  @Test
  public void getContextForTarget_invalidAllowedGrpcServicesTypeFallbackToEmpty() {
    BootstrapInfo bootstrapInfo = BootstrapInfo.builder().servers(ImmutableList.of())
        .node(DUMMY_NODE).allowedGrpcServices(Optional.of("invalid_type_string")).build();

    BootstrapInfoGrpcServiceContextProvider provider =
        new BootstrapInfoGrpcServiceContextProvider(bootstrapInfo, createServerInfo(false));

    GrpcServiceXdsContext context = provider.getContextForTarget("xds:///any");
    assertThat(context.validAllowedGrpcService().isPresent()).isFalse();
  }
}
