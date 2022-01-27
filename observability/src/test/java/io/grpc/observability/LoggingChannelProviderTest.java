/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import io.grpc.Grpc;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.TlsChannelCredentials;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyChannelProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LoggingChannelProviderTest {
  
  @Test
  public void init_usesNettyProvider() {
    LoggingChannelProvider.init();
    assertThat(LoggingChannelProvider.instance).isNotNull();
    NettyChannelProvider prevProvider = LoggingChannelProvider.instance.prevProvider;
    assertThat(prevProvider).isInstanceOf(NettyChannelProvider.class);
    assertThat(ManagedChannelProvider.provider()).isSameInstanceAs(LoggingChannelProvider.instance);
    try {
      LoggingChannelProvider.init();
      fail("should have failed for calling init() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("LoggingChannelProvider already initialized!");
    }
    LoggingChannelProvider.finish();
    assertThat(ManagedChannelProvider.provider()).isSameInstanceAs(prevProvider);
  }

  @Test
  public void builderForTarget() {
    LoggingChannelProvider.init();
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget("dns://localhost");
    assertThat(builder).isInstanceOf(NettyChannelBuilder.class);
    LoggingChannelProvider.finish();
  }

  @Test
  public void builderForAddress() {
    LoggingChannelProvider.init();
    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress("localhost", 80);
    assertThat(builder).isInstanceOf(NettyChannelBuilder.class);
    LoggingChannelProvider.finish();
  }

  @Test
  public void newChannelBuilder() {
    LoggingChannelProvider.init();
    ManagedChannelBuilder<?> builder = Grpc.newChannelBuilder("dns://localhost",
        TlsChannelCredentials.create());
    assertThat(builder).isInstanceOf(NettyChannelBuilder.class);
    LoggingChannelProvider.finish();
  }
}
