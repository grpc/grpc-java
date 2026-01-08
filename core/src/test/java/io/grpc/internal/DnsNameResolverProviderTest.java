/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import io.grpc.ChannelLogger;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.SynchronizationContext;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DnsNameResolverProvider}. */
@RunWith(JUnit4.class)
public class DnsNameResolverProviderTest {
  private final FakeClock fakeClock = new FakeClock();

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
      .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
      .build();

  private DnsNameResolverProvider provider = new DnsNameResolverProvider();

  @Test
  public void isAvailable() {
    assertTrue(provider.isAvailable());
  }

  @Test
  public void newNameResolver_acceptsHostAndPort() {
    NameResolver nameResolver = provider.newNameResolver(URI.create("dns:///localhost:443"), args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("localhost:443");
  }

  @Test
  public void newNameResolver_rejectsNonDnsScheme() {
    NameResolver nameResolver =
        provider.newNameResolver(URI.create("notdns:///localhost:443"), args);
    assertThat(nameResolver).isNull();
  }

  @Test
  public void newNameResolver_toleratesTrailingPathSegments() {
    NameResolver nameResolver =
        provider.newNameResolver(URI.create("dns:///foo.googleapis.com/ig/nor/ed"), args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void newNameResolver_toleratesAuthority() {
    NameResolver nameResolver =
        provider.newNameResolver(URI.create("dns://8.8.8.8/foo.googleapis.com"), args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }
}
