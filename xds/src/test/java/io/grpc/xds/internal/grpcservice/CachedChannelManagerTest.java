/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.grpcservice;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.ManagedChannel;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.GoogleGrpcConfig;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link CachedChannelManager}.
 */
@RunWith(JUnit4.class)
public class CachedChannelManagerTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private Function<GrpcServiceConfig, ManagedChannel> mockCreator;

  @Mock
  private ManagedChannel mockChannel1;

  @Mock
  private ManagedChannel mockChannel2;

  private CachedChannelManager manager;

  private GrpcServiceConfig config1;
  private GrpcServiceConfig config2;

  @Before
  public void setUp() {
    manager = new CachedChannelManager(mockCreator);

    config1 = buildConfig("authz.service.com", "creds1");
    config2 = buildConfig("authz.service.com", "creds2"); // Different creds instance
  }

  private GrpcServiceConfig buildConfig(String target, String credsType) {
    ChannelCredsConfig credsConfig = mock(ChannelCredsConfig.class);
    when(credsConfig.type()).thenReturn(credsType);
    
    ConfiguredChannelCredentials creds = ConfiguredChannelCredentials.create(
        mock(io.grpc.ChannelCredentials.class), credsConfig);
        
    GoogleGrpcConfig googleGrpc = GoogleGrpcConfig.builder()
        .target(target)
        .configuredChannelCredentials(creds)
        .build();
        
    return GrpcServiceConfig.newBuilder()
        .googleGrpc(googleGrpc)
        .initialMetadata(ImmutableList.of())
        .build();
  }

  @Test
  public void getChannel_sameConfig_returnsCached() {
    when(mockCreator.apply(config1)).thenReturn(mockChannel1);

    ManagedChannel channela = manager.getChannel(config1);
    ManagedChannel channelb = manager.getChannel(config1);

    assertThat(channela).isSameInstanceAs(mockChannel1);
    assertThat(channelb).isSameInstanceAs(mockChannel1);
    verify(mockCreator, org.mockito.Mockito.times(1)).apply(config1);
  }

  @Test
  public void getChannel_differentConfig_shutsDownOldAndReturnsNew() {
    when(mockCreator.apply(config1)).thenReturn(mockChannel1);
    when(mockCreator.apply(config2)).thenReturn(mockChannel2);

    ManagedChannel channel1 = manager.getChannel(config1);
    assertThat(channel1).isSameInstanceAs(mockChannel1);

    ManagedChannel channel2 = manager.getChannel(config2);
    assertThat(channel2).isSameInstanceAs(mockChannel2);

    verify(mockChannel1).shutdown();
    verify(mockCreator, org.mockito.Mockito.times(1)).apply(config1);
    verify(mockCreator, org.mockito.Mockito.times(1)).apply(config2);
  }

  @Test
  public void close_shutsDownChannel() {
    when(mockCreator.apply(config1)).thenReturn(mockChannel1);

    manager.getChannel(config1);
    manager.close();

    verify(mockChannel1).shutdown();
  }
}
