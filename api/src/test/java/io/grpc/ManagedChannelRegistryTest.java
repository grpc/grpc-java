/*
 * Copyright 2020 The gRPC Authors
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ManagedChannelRegistry}. */
@RunWith(JUnit4.class)
public class ManagedChannelRegistryTest {
  private String target = "testing123";
  private ChannelCredentials creds = new ChannelCredentials() {
    @Override
    public ChannelCredentials withoutBearerTokens() {
      throw new UnsupportedOperationException();
    }
  };

  @Test
  public void register_unavailableProviderThrows() {
    ManagedChannelRegistry reg = new ManagedChannelRegistry();
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
    ManagedChannelRegistry reg = new ManagedChannelRegistry();
    ManagedChannelProvider p1 = new BaseProvider(true, 5);
    ManagedChannelProvider p2 = new BaseProvider(true, 5);
    ManagedChannelProvider p3 = new BaseProvider(true, 5);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    assertThat(reg.providers()).containsExactly(p1, p2, p3).inOrder();
    reg.deregister(p2);
    assertThat(reg.providers()).containsExactly(p1, p3).inOrder();
  }

  @Test
  public void provider_sorted() {
    ManagedChannelRegistry reg = new ManagedChannelRegistry();
    ManagedChannelProvider p1 = new BaseProvider(true, 5);
    ManagedChannelProvider p2 = new BaseProvider(true, 3);
    ManagedChannelProvider p3 = new BaseProvider(true, 8);
    ManagedChannelProvider p4 = new BaseProvider(true, 3);
    ManagedChannelProvider p5 = new BaseProvider(true, 8);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    reg.register(p4);
    reg.register(p5);
    assertThat(reg.providers()).containsExactly(p3, p5, p1, p2, p4).inOrder();
  }

  @Test
  public void getProvider_noProvider() {
    assertThat(new ManagedChannelRegistry().provider()).isNull();
  }

  @Test
  public void newChannelBuilder_providerReturnsError() {
    final String errorString = "brisking";
    class ErrorProvider extends BaseProvider {
      ErrorProvider() {
        super(true, 5);
      }

      @Override
      public NewChannelBuilderResult newChannelBuilder(
          String passedTarget, ChannelCredentials passedCreds) {
        assertThat(passedTarget).isSameInstanceAs(target);
        assertThat(passedCreds).isSameInstanceAs(creds);
        return NewChannelBuilderResult.error(errorString);
      }
    }

    ManagedChannelRegistry registry = new ManagedChannelRegistry();
    registry.register(new ErrorProvider());
    try {
      registry.newChannelBuilder(target, creds);
      fail("expected exception");
    } catch (ManagedChannelRegistry.ProviderNotFoundException ex) {
      assertThat(ex).hasMessageThat().contains(errorString);
      assertThat(ex).hasMessageThat().contains(ErrorProvider.class.getName());
    }
  }

  @Test
  public void newChannelBuilder_providerReturnsNonNull() {
    ManagedChannelRegistry registry = new ManagedChannelRegistry();
    registry.register(new BaseProvider(true, 5) {
      @Override
      public NewChannelBuilderResult newChannelBuilder(
          String passedTarget, ChannelCredentials passedCreds) {
        return NewChannelBuilderResult.error("dodging");
      }
    });
    class MockChannelBuilder extends ForwardingChannelBuilder<MockChannelBuilder> {
      @Override public ManagedChannelBuilder<?> delegate() {
        throw new UnsupportedOperationException();
      }
    }

    final ManagedChannelBuilder<?> mcb = new MockChannelBuilder();
    registry.register(new BaseProvider(true, 4) {
      @Override
      public NewChannelBuilderResult newChannelBuilder(
          String passedTarget, ChannelCredentials passedCreds) {
        return NewChannelBuilderResult.channelBuilder(mcb);
      }
    });
    registry.register(new BaseProvider(true, 3) {
      @Override
      public NewChannelBuilderResult newChannelBuilder(
          String passedTarget, ChannelCredentials passedCreds) {
        fail("Should not be called");
        throw new AssertionError();
      }
    });
    assertThat(registry.newChannelBuilder(target, creds)).isSameInstanceAs(mcb);
  }

  @Test
  public void newChannelBuilder_noProvider() {
    ManagedChannelRegistry registry = new ManagedChannelRegistry();
    try {
      registry.newChannelBuilder(target, creds);
      fail("expected exception");
    } catch (ManagedChannelRegistry.ProviderNotFoundException ex) {
      assertThat(ex).hasMessageThat().contains("No functional channel service provider found");
      assertThat(ex).hasMessageThat().contains("grpc-netty");
    }
  }

  private static class BaseProvider extends ManagedChannelProvider {
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
    protected ManagedChannelBuilder<?> builderForAddress(String name, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected ManagedChannelBuilder<?> builderForTarget(String target) {
      throw new UnsupportedOperationException();
    }
  }
}
