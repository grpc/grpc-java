/*
 * Copyright 2015 The gRPC Authors
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
import static org.junit.Assert.fail;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for ManagedChannelImplBuilder#getNameResolverProvider(). */
@RunWith(JUnit4.class)
public class ManagedChannelImplGetNameResolverTest {
  @Test
  public void invalidUriTarget() {
    testInvalidTarget("defaultscheme:///[invalid]");
  }

  @Test
  public void validTargetWithInvalidDnsName() throws Exception {
    testValidTarget("[valid]", "defaultscheme:///%5Bvalid%5D",
        new URI("defaultscheme", "", "/[valid]", null));
  }

  @Test
  public void validAuthorityTarget() throws Exception {
    testValidTarget("foo.googleapis.com:8080", "defaultscheme:///foo.googleapis.com:8080",
        new URI("defaultscheme", "", "/foo.googleapis.com:8080", null));
  }

  @Test
  public void validUriTarget() throws Exception {
    testValidTarget("scheme:///foo.googleapis.com:8080", "scheme:///foo.googleapis.com:8080",
        new URI("scheme", "", "/foo.googleapis.com:8080", null));
  }

  @Test
  public void validIpv4AuthorityTarget() throws Exception {
    testValidTarget("127.0.0.1:1234", "defaultscheme:///127.0.0.1:1234",
        new URI("defaultscheme", "", "/127.0.0.1:1234", null));
  }

  @Test
  public void validIpv4UriTarget() throws Exception {
    testValidTarget("dns:///127.0.0.1:1234", "dns:///127.0.0.1:1234",
        new URI("dns", "", "/127.0.0.1:1234", null));
  }

  @Test
  public void validIpv6AuthorityTarget() throws Exception {
    testValidTarget("[::1]:1234", "defaultscheme:///%5B::1%5D:1234",
        new URI("defaultscheme", "", "/[::1]:1234", null));
  }

  @Test
  public void invalidIpv6UriTarget() throws Exception {
    testInvalidTarget("dns:///[::1]:1234");
  }

  @Test
  public void validIpv6UriTarget() throws Exception {
    testValidTarget("dns:///%5B::1%5D:1234", "dns:///%5B::1%5D:1234",
        new URI("dns", "", "/[::1]:1234", null));
  }

  @Test
  public void validTargetStartingWithSlash() throws Exception {
    testValidTarget("/target", "defaultscheme:////target",
        new URI("defaultscheme", "", "//target", null));
  }

  @Test
  public void validTargetNoProvider() {
    NameResolverRegistry nameResolverRegistry = new NameResolverRegistry();
    try {
      ManagedChannelImplBuilder.getNameResolverProvider(
          "foo.googleapis.com:8080", nameResolverRegistry,
          Collections.singleton(InetSocketAddress.class));
      fail("Should fail");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void validTargetProviderAddrTypesNotSupported() {
    NameResolverRegistry nameResolverRegistry = getTestRegistry("testscheme");
    try {
      ManagedChannelImplBuilder.getNameResolverProvider(
          "testscheme:///foo.googleapis.com:8080", nameResolverRegistry,
          Collections.singleton(CustomSocketAddress.class));
      fail("Should fail");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "Address types of NameResolver 'testscheme' for "
              + "'testscheme:///foo.googleapis.com:8080' not supported by transport");
    }
  }

  private void testValidTarget(String target, String expectedUriString, URI expectedUri) {
    NameResolverRegistry nameResolverRegistry = getTestRegistry(expectedUri.getScheme());
    ManagedChannelImplBuilder.ResolvedNameResolver resolved =
        ManagedChannelImplBuilder.getNameResolverProvider(
            target, nameResolverRegistry, Collections.singleton(InetSocketAddress.class));
    assertThat(resolved.provider).isInstanceOf(FakeNameResolverProvider.class);
    assertThat(resolved.targetUri).isEqualTo(expectedUri);
    assertThat(resolved.targetUri.toString()).isEqualTo(expectedUriString);
  }

  private void testInvalidTarget(String target) {
    NameResolverRegistry nameResolverRegistry = getTestRegistry("dns");

    try {
      ManagedChannelImplBuilder.ResolvedNameResolver resolved =
          ManagedChannelImplBuilder.getNameResolverProvider(
              target, nameResolverRegistry, Collections.singleton(InetSocketAddress.class));
      FakeNameResolverProvider nameResolverProvider = (FakeNameResolverProvider) resolved.provider;
      fail("Should have failed, but got resolver provider " + nameResolverProvider);
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  private static NameResolverRegistry getTestRegistry(String expectedScheme) {
    NameResolverRegistry nameResolverRegistry = new NameResolverRegistry();
    FakeNameResolverProvider nameResolverProvider = new FakeNameResolverProvider(expectedScheme);
    nameResolverRegistry.register(nameResolverProvider);
    return nameResolverRegistry;
  }

  private static class FakeNameResolverProvider extends NameResolverProvider {
    final String expectedScheme;

    FakeNameResolverProvider(String expectedScheme) {
      this.expectedScheme = expectedScheme;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      if (expectedScheme.equals(targetUri.getScheme())) {
        return new FakeNameResolver(targetUri);
      }
      return null;
    }

    @Override
    public String getDefaultScheme() {
      return expectedScheme;
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }
  }

  private static class FakeNameResolver extends NameResolver {
    final URI uri;

    FakeNameResolver(URI uri) {
      this.uri = uri;
    }

    @Override public String getServiceAuthority() {
      return uri.getAuthority();
    }

    @Override public void start(final Listener2 listener) {}

    @Override public void shutdown() {}
  }

  private static class CustomSocketAddress extends SocketAddress {}
}
