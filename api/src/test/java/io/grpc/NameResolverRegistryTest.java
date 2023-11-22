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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.internal.DnsNameResolverProvider;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameResolverRegistry}. */
@RunWith(JUnit4.class)
public class NameResolverRegistryTest {
  private final URI uri = URI.create("dns:///localhost");
  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(mock(ProxyDetector.class))
      .setSynchronizationContext(new SynchronizationContext(mock(UncaughtExceptionHandler.class)))
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .build();

  @Test
  public void register_unavilableProviderThrows() {
    NameResolverRegistry reg = new NameResolverRegistry();
    try {
      reg.register(new BaseProvider(false, 5));
      fail("Should throw");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("isAvailable() returned false");
    }
    assertThat(reg.providers()).isEmpty();
  }

  @Test
  public void deregister() {
    NameResolverRegistry reg = new NameResolverRegistry();
    NameResolverProvider p1 = new BaseProvider(true, 5);
    NameResolverProvider p2 = new BaseProvider(true, 5);
    NameResolverProvider p3 = new BaseProvider(true, 5);
    String sameScheme = p1.getDefaultScheme();
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    assertThat(reg.providers().get(sameScheme)).isSameInstanceAs(p1);
    reg.deregister(p2);
    assertThat(reg.providers().get(sameScheme)).isSameInstanceAs(p1);
    reg.deregister(p1);
    assertThat(reg.providers().get(sameScheme)).isSameInstanceAs(p3);

  }

  @Test
  public void provider_sorted() {
    NameResolverRegistry reg = new NameResolverRegistry();
    NameResolverProvider p1 = new BaseProvider(true, 5);
    NameResolverProvider p2 = new BaseProvider(true, 3);
    NameResolverProvider p3 = new BaseProvider(true, 8);
    NameResolverProvider p4 = new BaseProvider(true, 3);
    NameResolverProvider p5 = new BaseProvider(true, 8);
    String sameScheme = p1.getDefaultScheme();
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    reg.register(p4);
    reg.register(p5);
    assertThat(reg.providers().get(sameScheme)).isSameInstanceAs(p3);
  }

  @Test
  public void getDefaultScheme_noProvider() {
    NameResolver.Factory factory = new NameResolverRegistry().asFactory();
    assertThat(factory.getDefaultScheme()).isEqualTo("unknown");
  }

  @Test
  public void newNameResolver_providerReturnsNull() {
    NameResolverRegistry registry = new NameResolverRegistry();
    registry.register(
        new BaseProvider(true, 5, "noScheme") {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
            assertThat(passedUri).isSameInstanceAs(uri);
            assertThat(passedArgs).isSameInstanceAs(args);
            return null;
          }
        });
    assertThat(registry.asFactory().newNameResolver(uri, args)).isNull();
    assertThat(registry.asFactory().getDefaultScheme()).isEqualTo("noScheme");
  }

  @Test
  public void newNameResolver_providerReturnsNonNull() {
    NameResolverRegistry registry = new NameResolverRegistry();
    registry.register(new BaseProvider(true, 5, uri.getScheme()) {
      @Override
      public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
        return null;
      }
    });
    final NameResolver nr = new NameResolver() {
      @Override public String getServiceAuthority() {
        throw new UnsupportedOperationException();
      }

      @Override public void start(Listener2 listener) {
        throw new UnsupportedOperationException();
      }

      @Override public void shutdown() {
        throw new UnsupportedOperationException();
      }
    };
    registry.register(
        new BaseProvider(true, 4, uri.getScheme()) {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
            return nr;
          }
        });
    registry.register(
        new BaseProvider(true, 3, uri.getScheme()) {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
            fail("Should not be called");
            throw new AssertionError();
          }
        });
    assertThat(registry.asFactory().newNameResolver(uri, args)).isNull();
    assertThat(registry.asFactory().getDefaultScheme()).isEqualTo(uri.getScheme());
  }

  @Test
  public void newNameResolver_multipleScheme() {
    NameResolverRegistry registry = new NameResolverRegistry();
    registry.register(new BaseProvider(true, 5, uri.getScheme()) {
      @Override
      public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
        return null;
      }
    });
    final NameResolver nr = new NameResolver() {
      @Override public String getServiceAuthority() {
        throw new UnsupportedOperationException();
      }

      @Override public void start(Listener2 listener) {
        throw new UnsupportedOperationException();
      }

      @Override public void shutdown() {
        throw new UnsupportedOperationException();
      }
    };
    registry.register(
        new BaseProvider(true, 4, "other") {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Args passedArgs) {
            return nr;
          }
        });

    assertThat(registry.asFactory().newNameResolver(uri, args)).isNull();
    assertThat(registry.asFactory().newNameResolver(URI.create("/0.0.0.0:80"), args)).isNull();
    assertThat(registry.asFactory().newNameResolver(URI.create("///0.0.0.0:80"), args)).isNull();
    assertThat(registry.asFactory().newNameResolver(URI.create("other:///0.0.0.0:80"), args))
            .isSameInstanceAs(nr);
    assertThat(registry.asFactory().newNameResolver(URI.create("OTHER:///0.0.0.0:80"), args))
            .isSameInstanceAs(nr);
    assertThat(registry.asFactory().getDefaultScheme()).isEqualTo("dns");
  }

  @Test
  public void newNameResolver_noProvider() {
    NameResolver.Factory factory = new NameResolverRegistry().asFactory();
    assertThat(factory.newNameResolver(uri, args)).isNull();
    assertThat(factory.getDefaultScheme()).isEqualTo("unknown");
  }

  @Test
  public void baseProviders() {
    Map<String, NameResolverProvider> providers =
            NameResolverRegistry.getDefaultRegistry().providers();
    assertThat(providers).hasSize(1);
    // 2 name resolvers from grpclb and core, higher priority one is returned.
    assertThat(providers.get("dns").getClass().getName())
        .isEqualTo("io.grpc.grpclb.SecretGrpclbNameResolverProvider$Provider");
    assertThat(NameResolverRegistry.getDefaultRegistry().asFactory().getDefaultScheme())
        .isEqualTo("dns");
  }

  @Test
  public void getClassesViaHardcoded_classesPresent() throws Exception {
    List<Class<?>> classes = NameResolverRegistry.getHardCodedClasses();
    assertThat(classes).containsExactly(io.grpc.internal.DnsNameResolverProvider.class);
  }

  @Test
  public void provided() {
    for (NameResolverProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
        NameResolverProvider.class, getClass().getClassLoader())) {
      if (current instanceof DnsNameResolverProvider) {
        return;
      }
    }
    fail("DnsNameResolverProvider not registered");
  }

  private static class BaseProvider extends NameResolverProvider {
    private final boolean isAvailable;
    private final int priority;
    private final String scheme;

    public BaseProvider(boolean isAvailable, int priority) {
      this.isAvailable = isAvailable;
      this.priority = priority;
      this.scheme = null;
    }

    public BaseProvider(boolean isAvailable, int priority, String scheme) {
      this.isAvailable = isAvailable;
      this.priority = priority;
      this.scheme = scheme;
    }

    @Override
    protected boolean isAvailable() {
      return isAvailable;
    }

    @Override
    protected int priority() {
      return priority;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getDefaultScheme() {
      return scheme == null ? "scheme" + getClass().getSimpleName() : scheme;
    }
  }
}
