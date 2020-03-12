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

package io.grpc.grpclb;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.ChannelLogger;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.SynchronizationContext;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.internal.GrpcUtil;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SecretGrpclbNameResolverProvider}. */
@RunWith(JUnit4.class)
public class SecretGrpclbNameResolverProviderTest {

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(GrpcUtil.DEFAULT_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .build();

  private SecretGrpclbNameResolverProvider.Provider provider =
      new SecretGrpclbNameResolverProvider.Provider();

  @Test
  public void isAvailable() {
    assertThat(provider.isAvailable()).isTrue();
  }

  @Test
  public void priority_shouldBeHigherThanDefaultDnsNameResolver() {
    DnsNameResolverProvider defaultDnsNameResolver = new DnsNameResolverProvider();

    assertThat(provider.priority()).isGreaterThan(defaultDnsNameResolver.priority());
  }

  @Test
  public void newNameResolver() {
    assertThat(provider.newNameResolver(URI.create("dns:///localhost:443"), args))
        .isInstanceOf(GrpclbNameResolver.class);
    assertThat(provider.newNameResolver(URI.create("notdns:///localhost:443"), args)).isNull();
  }

  @Test
  public void invalidDnsName() throws Exception {
    testInvalidUri(new URI("dns", null, "/[invalid]", null));
  }

  @Test
  public void validIpv6() throws Exception {
    testValidUri(new URI("dns", null, "/[::1]", null));
  }

  @Test
  public void validDnsNameWithoutPort() throws Exception {
    testValidUri(new URI("dns", null, "/foo.googleapis.com", null));
  }

  @Test
  public void validDnsNameWithPort() throws Exception {
    testValidUri(new URI("dns", null, "/foo.googleapis.com:456", null));
  }

  private void testInvalidUri(URI uri) {
    try {
      provider.newNameResolver(uri, args);
      fail("Should have failed");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  private void testValidUri(URI uri) {
    GrpclbNameResolver resolver = provider.newNameResolver(uri, args);
    assertThat(resolver).isNotNull();
  }
}
