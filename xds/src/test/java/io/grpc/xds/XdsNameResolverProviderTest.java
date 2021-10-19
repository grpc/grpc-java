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

import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsNameResolverProvider}. */
@RunWith(JUnit4.class)
public class XdsNameResolverProviderTest {
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
      .setChannelLogger(mock(ChannelLogger.class))
      .build();

  private XdsNameResolverProvider provider = new XdsNameResolverProvider();

  @Test
  public void provided() {
    for (NameResolverProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
        NameResolverProvider.class, getClass().getClassLoader())) {
      if (current instanceof XdsNameResolverProvider) {
        return;
      }
    }
    fail("XdsNameResolverProvider not registered");
  }

  @Test
  public void isAvailable() {
    assertThat(provider.isAvailable()).isTrue();
  }

  @Test
  public void newNameResolver() {
    assertThat(
        provider.newNameResolver(URI.create("xds://1.1.1.1/foo.googleapis.com"), args))
        .isInstanceOf(XdsNameResolver.class);
    assertThat(
        provider.newNameResolver(URI.create("xds:///foo.googleapis.com"), args))
        .isInstanceOf(XdsNameResolver.class);
    assertThat(
        provider.newNameResolver(URI.create("notxds://1.1.1.1/foo.googleapis.com"),
            args))
        .isNull();
  }

  @Test
  public void validName_withAuthority() {
    XdsNameResolver resolver =
        provider.newNameResolver(
            URI.create("xds://trafficdirector.google.com/foo.googleapis.com"), args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void validName_noAuthority() {
    XdsNameResolver resolver =
        provider.newNameResolver(URI.create("xds:///foo.googleapis.com"), args);
    assertThat(resolver).isNotNull();
    assertThat(resolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void invalidName_hostnameContainsUnderscore() {
    URI uri = URI.create("xds:///foo_bar.googleapis.com");
    try {
      provider.newNameResolver(uri, args);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      // Expected
    }
  }

  @Test
  public void newProvider_multipleScheme() {
    NameResolverRegistry registry = NameResolverRegistry.getDefaultRegistry();
    XdsNameResolverProvider provider0 = XdsNameResolverProvider.createForTest("no-scheme", null);
    registry.register(provider0);
    XdsNameResolverProvider provider1 = XdsNameResolverProvider.createForTest("new-xds-scheme",
            new HashMap<String, String>());
    registry.register(provider1);
    assertThat(registry.asFactory()
            .newNameResolver(URI.create("xds:///localhost"), args)).isNotNull();
    assertThat(registry.asFactory()
            .newNameResolver(URI.create("new-xds-scheme:///localhost"), args)).isNotNull();
    assertThat(registry.asFactory()
            .newNameResolver(URI.create("no-scheme:///localhost"), args)).isNotNull();
    registry.deregister(provider1);
    assertThat(registry.asFactory()
            .newNameResolver(URI.create("new-xds-scheme:///localhost"), args)).isNull();
    registry.deregister(provider0);
    assertThat(registry.asFactory()
            .newNameResolver(URI.create("xds:///localhost"), args)).isNotNull();
  }

  @Test
  public void newProvider_overrideBootstrap() {
    Map<String, ?> b = ImmutableMap.of(
            "node", ImmutableMap.of(
                    "id", "ENVOY_NODE_ID",
                    "cluster", "ENVOY_CLUSTER"),
            "xds_servers", Collections.singletonList(
                    ImmutableMap.of(
                            "server_uri", "trafficdirector.googleapis.com:443",
                            "channel_creds", Collections.singletonList(
                                    ImmutableMap.of("type", "insecure")
                            )
                    )
            )
    );
    NameResolverRegistry registry = new NameResolverRegistry();
    XdsNameResolverProvider provider = XdsNameResolverProvider.createForTest("no-scheme", b);
    registry.register(provider);
    NameResolver resolver = registry.asFactory()
            .newNameResolver(URI.create("no-scheme:///localhost"), args);
    resolver.start(mock(NameResolver.Listener2.class));
    assertThat(resolver).isInstanceOf(XdsNameResolver.class);
    assertThat(((XdsNameResolver)resolver).getXdsClient().getBootstrapInfo().node().getId())
            .isEqualTo("ENVOY_NODE_ID");
    resolver.shutdown();
    registry.deregister(provider);
  }
}
