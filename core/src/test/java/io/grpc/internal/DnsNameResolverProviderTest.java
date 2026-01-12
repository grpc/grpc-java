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
import static com.google.common.truth.TruthJUnit.assume;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import io.grpc.ChannelLogger;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.SynchronizationContext;
import io.grpc.Uri;
import java.net.URI;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Unit tests for {@link DnsNameResolverProvider}. */
@RunWith(Parameterized.class)
public class DnsNameResolverProviderTest {
  private final FakeClock fakeClock = new FakeClock();

  @Parameters(name = "enableRfc3986UrisParam={0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {{true}, {false}});
  }

  @Parameter public boolean enableRfc3986UrisParam;

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
    NameResolver nameResolver = newNameResolver("dns:///localhost:443", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("localhost:443");
    assertThat(((DnsNameResolver) nameResolver).getPort()).isEqualTo(443);
  }

  @Test
  public void newNameResolver_acceptsRootless() {
    assume().that(enableRfc3986UrisParam).isTrue();
    NameResolver nameResolver = newNameResolver("dns:localhost:443", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("localhost:443");
  }

  @Test
  public void newNameResolver_rejectsNonDnsScheme() {
    NameResolver nameResolver = newNameResolver("notdns:///localhost:443", args);
    assertThat(nameResolver).isNull();
  }

  @Test
  public void newNameResolver_validDnsNameWithoutPort_usesDefaultPort() {
    DnsNameResolver nameResolver =
        (DnsNameResolver) newNameResolver("dns:/foo.googleapis.com", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
    assertThat(nameResolver.getPort()).isEqualTo(args.getDefaultPort());
  }

  @Test
  public void newNameResolver_toleratesTrailingPathSegments() {
    NameResolver nameResolver = newNameResolver("dns:///foo.googleapis.com/ig/nor/ed", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void newNameResolver_toleratesAuthority() {
    NameResolver nameResolver = newNameResolver("dns://8.8.8.8/foo.googleapis.com", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("foo.googleapis.com");
  }

  @Test
  public void newNameResolver_validIpv6Host() {
    NameResolver nameResolver = newNameResolver("dns:/%5B::1%5D", args);
    assertThat(nameResolver).isNotNull();
    assertThat(nameResolver.getClass()).isSameInstanceAs(DnsNameResolver.class);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo("[::1]");
  }

  @Test
  public void newNameResolver_invalidIpv6Host_throws() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> newNameResolver("dns:/%5Binvalid%5D", args));
    assertThat(e).hasMessageThat().contains("invalid");
  }

  private NameResolver newNameResolver(String uriString, NameResolver.Args args) {
    return enableRfc3986UrisParam
        ? provider.newNameResolver(Uri.create(uriString), args)
        : provider.newNameResolver(URI.create(uriString), args);
  }
}
