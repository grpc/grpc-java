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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.InternalServiceProviders;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelProvider.NewChannelBuilderResult;
import io.grpc.ManagedChannelRegistryAccessor;
import io.grpc.TlsChannelCredentials;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NettyChannelProvider}. */
@RunWith(JUnit4.class)
public class NettyChannelProviderTest {
  private NettyChannelProvider provider = new NettyChannelProvider();

  @Test
  public void provided() {
    for (ManagedChannelProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
            ManagedChannelProvider.class, getClass().getClassLoader())) {
      if (current instanceof NettyChannelProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load NettyChannelProvider");
  }

  @Test
  public void providedHardCoded() {
    for (Class<?> current : ManagedChannelRegistryAccessor.getHardCodedClasses()) {
      if (current == NettyChannelProvider.class) {
        return;
      }
    }
    fail("Hard coded unable to load NettyChannelProvider");
  }

  @Test
  public void basicMethods() {
    assertTrue(provider.isAvailable());
    assertEquals(5, provider.priority());
  }

  @Test
  public void builderIsANettyBuilder() {
    assertSame(NettyChannelBuilder.class, provider.builderForAddress("localhost", 443).getClass());
  }

  @Test
  public void builderForTarget() {
    assertThat(provider.builderForTarget("localhost:443")).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void newChannelBuilder_success() {
    NewChannelBuilderResult result =
        provider.newChannelBuilder("localhost:443", TlsChannelCredentials.create());
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void newChannelBuilder_fail() {
    NewChannelBuilderResult result = provider.newChannelBuilder("localhost:443",
        TlsChannelCredentials.newBuilder().requireFakeFeature().build());
    assertThat(result.getError()).contains("FAKE");
  }

  @Test
  public void newChannelBuilder_withRegistry() {
    io.grpc.NameResolverRegistry registry = new io.grpc.NameResolverRegistry();
    NewChannelBuilderResult result = provider.newChannelBuilder(
        "localhost:443", TlsChannelCredentials.create(), registry, null);
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void newChannelBuilder_withProvider() {
    io.grpc.NameResolverProvider resolverProvider = new io.grpc.NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "dns";
      }

      @Override
      public io.grpc.NameResolver newNameResolver(java.net.URI targetUri,
          io.grpc.NameResolver.Args args) {
        return null;
      }
    };
    NewChannelBuilderResult result = provider.newChannelBuilder(
        "localhost:443", TlsChannelCredentials.create(), null,
        resolverProvider);
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void newChannelBuilder_registryPropagation_e2e() {
    String scheme = "testscheme";
    final io.grpc.NameResolverRegistry registry = new io.grpc.NameResolverRegistry();
    final java.util.concurrent.atomic.AtomicReference<io.grpc.NameResolverRegistry>
        capturedRegistry = new java.util.concurrent.atomic.AtomicReference<>();

    final io.grpc.NameResolverProvider resolverProvider = new io.grpc.NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return scheme;
      }

      @Override
      public io.grpc.NameResolver newNameResolver(java.net.URI targetUri,
          io.grpc.NameResolver.Args args) {
        capturedRegistry.set(args.getNameResolverRegistry());
        return new io.grpc.NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "authority";
          }

          @Override
          public void start(Listener2 listener) {
          }

          @Override
          public void shutdown() {
          }
        };
      }
    };
    registry.register(resolverProvider);

    NewChannelBuilderResult result = provider.newChannelBuilder(
        scheme + ":///target", TlsChannelCredentials.create(), registry,
        null);
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
    // Verify build() succeeds
    result.getChannelBuilder().build();

    // Verify the registry passed to args is the exact same instance
    assertSame("Registry should be propagated to NameResolver.Args", registry,
        capturedRegistry.get());

    // Verify default registry (empty) fails
    NewChannelBuilderResult defaultResult = provider.newChannelBuilder(
        scheme + ":///target", TlsChannelCredentials.create(),
        new io.grpc.NameResolverRegistry(), null);
    // The provider might still return a builder, but build() should fail if it
    // can't find the resolver.
    // However, NettyChannelProvider just delegates to NettyChannelBuilder.
    // NettyChannelBuilder delegates to ManagedChannelImplBuilder.
    // ManagedChannelImplBuilder.build() calls getNameResolverProvider(), which
    // throws if not found.
    try {
      defaultResult.getChannelBuilder().build();
      fail("Should have failed to build() without correct registry");
    } catch (IllegalArgumentException e) {
      // Expected
    }
  }

  @Test
  public void newChannelBuilder_providerPropagation_e2e() {
    String scheme = "otherscheme";
    final io.grpc.NameResolverProvider resolverProvider = new io.grpc.NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return scheme;
      }

      @Override
      public io.grpc.NameResolver newNameResolver(java.net.URI targetUri,
          io.grpc.NameResolver.Args args) {
        return new io.grpc.NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "authority";
          }

          @Override
          public void start(Listener2 listener) {
          }

          @Override
          public void shutdown() {
          }
        };
      }
    };

    // Pass explicit provider, null registry
    NewChannelBuilderResult result = provider.newChannelBuilder(
        scheme + ":///target", TlsChannelCredentials.create(),
        null, resolverProvider);
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
    // Should succeed because we passed the specific provider
    result.getChannelBuilder().build();
  }
}
