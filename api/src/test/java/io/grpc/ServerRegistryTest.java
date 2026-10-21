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

/** Unit tests for {@link ServerRegistry}. */
@RunWith(JUnit4.class)
public class ServerRegistryTest {
  private int port = 123;
  private ServerCredentials creds = new ServerCredentials() {};

  @Test
  public void register_unavailableProviderThrows() {
    ServerRegistry reg = new ServerRegistry();
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
    ServerRegistry reg = new ServerRegistry();
    ServerProvider p1 = new BaseProvider(true, 5);
    ServerProvider p2 = new BaseProvider(true, 5);
    ServerProvider p3 = new BaseProvider(true, 5);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    assertThat(reg.providers()).containsExactly(p1, p2, p3).inOrder();
    reg.deregister(p2);
    assertThat(reg.providers()).containsExactly(p1, p3).inOrder();
  }

  @Test
  public void provider_sorted() {
    ServerRegistry reg = new ServerRegistry();
    ServerProvider p1 = new BaseProvider(true, 5);
    ServerProvider p2 = new BaseProvider(true, 3);
    ServerProvider p3 = new BaseProvider(true, 8);
    ServerProvider p4 = new BaseProvider(true, 3);
    ServerProvider p5 = new BaseProvider(true, 8);
    reg.register(p1);
    reg.register(p2);
    reg.register(p3);
    reg.register(p4);
    reg.register(p5);
    assertThat(reg.providers()).containsExactly(p3, p5, p1, p2, p4).inOrder();
  }

  @Test
  public void getProvider_noProvider() {
    assertThat(new ServerRegistry().provider()).isNull();
  }

  @Test
  public void newServerBuilderForPort_providerReturnsError() {
    final String errorString = "brisking";
    class ErrorProvider extends BaseProvider {
      ErrorProvider() {
        super(true, 5);
      }

      @Override
      public NewServerBuilderResult newServerBuilderForPort(
          int passedPort, ServerCredentials passedCreds) {
        assertThat(passedPort).isEqualTo(port);
        assertThat(passedCreds).isSameInstanceAs(creds);
        return NewServerBuilderResult.error(errorString);
      }
    }

    ServerRegistry registry = new ServerRegistry();
    registry.register(new ErrorProvider());
    try {
      registry.newServerBuilderForPort(port, creds);
      fail("expected exception");
    } catch (ServerRegistry.ProviderNotFoundException ex) {
      assertThat(ex).hasMessageThat().contains(errorString);
      assertThat(ex).hasMessageThat().contains(ErrorProvider.class.getName());
    }
  }

  @Test
  public void newServerBuilderForPort_providerReturnsNonNull() {
    ServerRegistry registry = new ServerRegistry();
    registry.register(new BaseProvider(true, 5) {
      @Override
      public NewServerBuilderResult newServerBuilderForPort(
          int passedPort, ServerCredentials passedCreds) {
        return NewServerBuilderResult.error("dodging");
      }
    });
    class MockServerBuilder extends ForwardingServerBuilder<MockServerBuilder> {
      @Override public ServerBuilder<?> delegate() {
        throw new UnsupportedOperationException();
      }
    }

    final ServerBuilder<?> mcb = new MockServerBuilder();
    registry.register(new BaseProvider(true, 4) {
      @Override
      public NewServerBuilderResult newServerBuilderForPort(
          int passedPort, ServerCredentials passedCreds) {
        return NewServerBuilderResult.serverBuilder(mcb);
      }
    });
    registry.register(new BaseProvider(true, 3) {
      @Override
      public NewServerBuilderResult newServerBuilderForPort(
          int passedPort, ServerCredentials passedCreds) {
        fail("Should not be called");
        throw new AssertionError();
      }
    });
    assertThat(registry.newServerBuilderForPort(port, creds)).isSameInstanceAs(mcb);
  }

  @Test
  public void newServerBuilderForPort_noProvider() {
    ServerRegistry registry = new ServerRegistry();
    try {
      registry.newServerBuilderForPort(port, creds);
      fail("expected exception");
    } catch (ServerRegistry.ProviderNotFoundException ex) {
      assertThat(ex).hasMessageThat().contains("No functional server found");
      assertThat(ex).hasMessageThat().contains("grpc-netty");
    }
  }

  private static class BaseProvider extends ServerProvider {
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
    protected ServerBuilder<?> builderForPort(int port) {
      throw new UnsupportedOperationException();
    }
  }
}
