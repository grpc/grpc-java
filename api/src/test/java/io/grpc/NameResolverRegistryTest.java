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
import static org.mockito.Mockito.verifyZeroInteractions;

import io.grpc.internal.DnsNameResolverProvider;
import java.net.URI;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameResolverRegistry}. */
@RunWith(JUnit4.class)
public class NameResolverRegistryTest {
  private final URI uri = URI.create("dns:///localhost");
  private final NameResolver.Helper helper = mock(NameResolver.Helper.class);

  @After
  public void wrapUp() {
    // The helper is not implemented.  Make sure it's not used in the test.
    verifyZeroInteractions(helper);
  }

  @Test
  public void register_unavilableProviderThrows() {
    NameResolverRegistry reg = new NameResolverRegistry();
    try {
      reg.register(new BaseProvider(false, 5));
      fail("Should throw");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains("isAvailable() returned false");
    }
    assertThat(reg.providers()).isEmpty();
  }

  @Test
  public void deregister() {
    NameResolverRegistry reg = new NameResolverRegistry();
    NameResolverProvider p1 = new BaseProvider(true, 5);
    NameResolverProvider p2 = new BaseProvider(true, 5);
    NameResolverProvider p3 = new BaseProvider(true, 5);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    assertThat(reg.providers()).containsExactly(p1, p2, p3).inOrder();
    reg.deregister(p2);
    assertThat(reg.providers()).containsExactly(p1, p3).inOrder();
  }

  @Test
  public void provider_sorted() {
    NameResolverRegistry reg = new NameResolverRegistry();
    NameResolverProvider p1 = new BaseProvider(true, 5);
    NameResolverProvider p2 = new BaseProvider(true, 3);
    NameResolverProvider p3 = new BaseProvider(true, 8);
    NameResolverProvider p4 = new BaseProvider(true, 3);
    NameResolverProvider p5 = new BaseProvider(true, 8);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    reg.register(p4);
    reg.register(p5);
    assertThat(reg.providers()).containsExactly(p3, p5, p1, p2, p4).inOrder();
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
        new BaseProvider(true, 5) {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Helper passedHelper) {
            assertThat(passedUri).isSameInstanceAs(uri);
            assertThat(passedHelper).isSameInstanceAs(helper);
            return null;
          }
        });
    assertThat(registry.asFactory().newNameResolver(uri, helper)).isNull();
  }

  @Test
  public void newNameResolver_providerReturnsNonNull() {
    NameResolverRegistry registry = new NameResolverRegistry();
    registry.register(new BaseProvider(true, 5) {
      @Override
      public NameResolver newNameResolver(URI passedUri, NameResolver.Helper passedHelper) {
        return null;
      }
    });
    final NameResolver nr = new NameResolver() {
      @Override public String getServiceAuthority() {
        throw new UnsupportedOperationException();
      }

      @Override public void start(Observer observer) {
        throw new UnsupportedOperationException();
      }

      @Override public void shutdown() {
        throw new UnsupportedOperationException();
      }
    };
    registry.register(
        new BaseProvider(true, 4) {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Helper passedHelper) {
            return nr;
          }
        });
    registry.register(
        new BaseProvider(true, 3) {
          @Override
          public NameResolver newNameResolver(URI passedUri, NameResolver.Helper passedHelper) {
            fail("Should not be called");
            throw new AssertionError();
          }
        });
    assertThat(registry.asFactory().newNameResolver(uri, helper)).isSameInstanceAs(nr);
  }

  @Test
  public void newNameResolver_noProvider() {
    NameResolver.Factory factory = new NameResolverRegistry().asFactory();
    assertThat(factory.newNameResolver(uri, helper)).isNull();
  }

  @Test
  public void baseProviders() {
    List<NameResolverProvider> providers = NameResolverRegistry.getDefaultRegistry().providers();
    assertThat(providers).hasSize(1);
    assertThat(providers.get(0)).isInstanceOf(DnsNameResolverProvider.class);
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

    public BaseProvider(boolean isAvailable, int priority) {
      this.isAvailable = isAvailable;
      this.priority = priority;
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
    public NameResolver newNameResolver(URI targetUri, NameResolver.Helper helper) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getDefaultScheme() {
      return "scheme" + getClass().getSimpleName();
    }
  }
}
