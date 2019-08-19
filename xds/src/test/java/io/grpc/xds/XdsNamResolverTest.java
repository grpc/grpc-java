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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Iterables;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsNameResolver}. */
@RunWith(JUnit4.class)
public class XdsNamResolverTest {

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
  @Captor private ArgumentCaptor<ResolutionResult> resultCaptor;

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

  @Test
  public void resolve_hardcodedResult() {
    XdsNameResolver resolver = newResolver("foo.googleapis.com");
    resolver.start(mockListener);
    verify(mockListener).onResult(resultCaptor.capture());
    assertHardCodedServiceConfig(resultCaptor.getValue());

    resolver = newResolver("bar.googleapis.com");
    resolver.start(mockListener);
    verify(mockListener, times(2)).onResult(resultCaptor.capture());
    assertHardCodedServiceConfig(resultCaptor.getValue());
  }

  @SuppressWarnings("unchecked")
  private static void assertHardCodedServiceConfig(ResolutionResult actualResult) {
    assertThat(actualResult.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig =
        actualResult.getAttributes().get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> xdsLbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(xdsLbConfig.keySet()).containsExactly("xds_experimental");
    Map<String, ?> rawConfigValues = (Map<String, ?>) xdsLbConfig.get("xds_experimental");
    assertThat(rawConfigValues)
        .containsExactly("childPolicy",
            Collections.singletonList(
                Collections.singletonMap("round_robin", Collections.EMPTY_MAP)));
  }

  private XdsNameResolver newResolver(String name) {
    return new XdsNameResolver(name);
  }
}
