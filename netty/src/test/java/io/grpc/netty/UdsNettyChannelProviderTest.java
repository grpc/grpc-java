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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InternalServiceProviders;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelProvider.NewChannelBuilderResult;
import io.grpc.ManagedChannelRegistryAccessor;
import io.grpc.TlsChannelCredentials;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link UdsNettyChannelProvider}. */
@RunWith(JUnit4.class)
public class UdsNettyChannelProviderTest {
  private UdsNettyChannelProvider provider = new UdsNettyChannelProvider();

  @Test
  public void provided() {
    for (ManagedChannelProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
            ManagedChannelProvider.class, getClass().getClassLoader())) {
      if (current instanceof UdsNettyChannelProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load UdsNettyChannelProvider");
  }

  @Test
  public void providedHardCoded() {
    for (Class<?> current : ManagedChannelRegistryAccessor.getHardCodedClasses()) {
      if (current == UdsNettyChannelProvider.class) {
        return;
      }
    }
    fail("Hard coded unable to load UdsNettyChannelProvider");
  }

  @Test
  public void basicMethods() {
    assertTrue(provider.isAvailable());
    assertEquals(3, provider.priority());
  }

  @Test
  public void builderForTarget() {
    assertThat(provider.builderForTarget("unix:sock.sock")).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void builderForTarget_badScheme() {
    try {
      provider.builderForTarget("dns:sock.sock");
      fail("exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().isEqualTo("scheme must be unix");
    }
  }

  @Test
  public void newChannelBuilder_success() {
    NewChannelBuilderResult result =
        provider.newChannelBuilder("unix:sock.sock", TlsChannelCredentials.create());
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void newChannelBuilder_badScheme() {
    try {
      provider.newChannelBuilder("dns:sock.sock", InsecureChannelCredentials.create());
      fail("exception expected");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().isEqualTo("scheme must be unix");
    }
  }

  @Test
  public void managedChannelRegistry_newChannelBuilder() {
    ManagedChannelBuilder<?> managedChannelBuilder
            = Grpc.newChannelBuilder("unix:///sock.sock", InsecureChannelCredentials.create());
    assertThat(managedChannelBuilder).isNotNull();
    ManagedChannel channel = managedChannelBuilder.build();
    assertThat(channel).isNotNull();
    assertThat(channel.authority()).isEqualTo("/sock.sock");
    channel.shutdownNow();
  }
}
