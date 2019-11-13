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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsNameResolver}. */
@RunWith(JUnit4.class)
public class XdsNameResolverTest {
  private static final Node FAKE_BOOTSTRAP_NODE =
      Node.newBuilder().setBuildVersion("fakeVer").build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final NameResolver.Args args =
      NameResolver.Args.newBuilder()
          .setDefaultPort(8080)
          .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
          .setSynchronizationContext(syncContext)
          .setServiceConfigParser(mock(ServiceConfigParser.class))
          .build();

  private final XdsNameResolverProvider provider = new XdsNameResolverProvider();

  @Mock private NameResolver.Listener2 mockListener;

  @Test
  public void validName_withAuthority() {
    XdsNameResolver resolver =
        provider.newNameResolver(
            URI.create("xds-experimental://trafficdirector.google.com/foo.googleapis.com"), args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void validName_noAuthority() {
    XdsNameResolver resolver =
        provider.newNameResolver(URI.create("xds-experimental:///foo.googleapis.com"), args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void invalidName_hostnameContainsUnderscore() {
    try {
      provider.newNameResolver(URI.create("xds-experimental:///foo_bar.googleapis.com"), args);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      // Expected
    }
  }

  /**
   * Each resolver explicitly triggers reading bootstrap configurations.
   */
  @Test
  public void eachResolverReadsBootstrapSeparately() {
    final ChannelCreds loasCreds = new ChannelCreds("loas2", null);
    final ChannelCreds googleDefaultCreds = new ChannelCreds("google_default", null);
    Bootstrapper bootstrapper1 = new Bootstrapper() {
      @Override
      BootstrapInfo readBootstrap() throws Exception {
        return new BootstrapInfo("trafficdirector-foo.googleapis.com",
            ImmutableList.of(googleDefaultCreds), FAKE_BOOTSTRAP_NODE);
      }
    };
    Bootstrapper bootstrapper2 = new Bootstrapper() {
      @Override
      BootstrapInfo readBootstrap() throws Exception {
        return new BootstrapInfo("trafficdirector-bar.googleapis.com",
            ImmutableList.of(loasCreds), FAKE_BOOTSTRAP_NODE);
      }
    };

    XdsNameResolver resolver1 = new XdsNameResolver("service-foo.googleapis.com", bootstrapper1);
    XdsNameResolver resolver2 = new XdsNameResolver("service-bar.googleapis.com", bootstrapper2);
    NameResolver.Listener2 listener1 = mock(NameResolver.Listener2.class);
    NameResolver.Listener2 listener2 = mock(NameResolver.Listener2.class);
    ArgumentCaptor<ResolutionResult> resultCaptor = ArgumentCaptor.forClass(null);

    resolver1.start(listener1);
    verify(listener1).onResult(resultCaptor.capture());
    ResolutionResult result1 = resultCaptor.getValue();
    assertThat(result1.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig1 =
        result1.getAttributes().get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
    Map<String, ?> xdsLbConfig1 = extractXdsLoadBalancingConfigFromServiceConfig(serviceConfig1);
    assertThat(xdsLbConfig1)
        .containsExactly(
            "balancerName",
            "trafficdirector-foo.googleapis.com",
            "childPolicy",
            Collections.singletonList(
                Collections.singletonMap("round_robin", Collections.EMPTY_MAP)));
    assertThat(result1.getAttributes().get(XdsNameResolver.XDS_NODE))
        .isEqualTo(FAKE_BOOTSTRAP_NODE);
    assertThat(result1.getAttributes().get(XdsNameResolver.XDS_CHANNEL_CREDS_LIST))
        .containsExactly(googleDefaultCreds);

    resolver2.start(listener2);
    verify(listener2).onResult(resultCaptor.capture());
    ResolutionResult result2 = resultCaptor.getValue();
    assertThat(result2.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig2 =
        result2.getAttributes().get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
    Map<String, ?> xdsLbConfig2 = extractXdsLoadBalancingConfigFromServiceConfig(serviceConfig2);
    assertThat(xdsLbConfig2)
        .containsExactly(
            "balancerName",
            "trafficdirector-bar.googleapis.com",
            "childPolicy",
            Collections.singletonList(
                Collections.singletonMap("round_robin", Collections.EMPTY_MAP)));
    assertThat(result2.getAttributes().get(XdsNameResolver.XDS_NODE))
        .isEqualTo(FAKE_BOOTSTRAP_NODE);
    assertThat(result2.getAttributes().get(XdsNameResolver.XDS_CHANNEL_CREDS_LIST))
        .containsExactly(loasCreds);

  }

  @Test
  public void resolve_bootstrapResult() {
    final ChannelCreds loasCreds = new ChannelCreds("loas2", null);
    final ChannelCreds googleDefaultCreds = new ChannelCreds("google_default", null);
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override
      BootstrapInfo readBootstrap() {
        return new BootstrapInfo("trafficdirector.googleapis.com",
            ImmutableList.of(loasCreds, googleDefaultCreds), FAKE_BOOTSTRAP_NODE);
      }
    };
    XdsNameResolver resolver = new XdsNameResolver("foo.googleapis.com", bootstrapper);
    resolver.start(mockListener);
    ArgumentCaptor<ResolutionResult> resultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();

    Map<String, ?> serviceConfig =
        result.getAttributes().get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
    Map<String, ?> rawConfigValues = extractXdsLoadBalancingConfigFromServiceConfig(serviceConfig);
    assertThat(rawConfigValues)
        .containsExactly(
            "balancerName",
            "trafficdirector.googleapis.com",
            "childPolicy",
            Collections.singletonList(
                Collections.singletonMap("round_robin", Collections.EMPTY_MAP)));
    assertThat(result.getAttributes().get(XdsNameResolver.XDS_NODE)).isEqualTo(FAKE_BOOTSTRAP_NODE);
    assertThat(result.getAttributes().get(XdsNameResolver.XDS_CHANNEL_CREDS_LIST))
        .containsExactly(loasCreds, googleDefaultCreds);
  }

  @Test
  public void resolve_failToBootstrap() {
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override
      BootstrapInfo readBootstrap() throws Exception {
        throw new IOException("Fail to read bootstrap file");
      }
    };

    XdsNameResolver resolver = new XdsNameResolver("foo.googleapis.com", bootstrapper);
    resolver.start(mockListener);
    ArgumentCaptor<Status> errorCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo("Failed to bootstrap");
    assertThat(error.getCause()).hasMessageThat().isEqualTo("Fail to read bootstrap file");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> extractXdsLoadBalancingConfigFromServiceConfig(
      Map<String, ?> serviceConfig) {
    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> xdsLbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(xdsLbConfig.keySet()).containsExactly("xds_experimental");

    return (Map<String, ?>) xdsLbConfig.get("xds_experimental");
  }
}
