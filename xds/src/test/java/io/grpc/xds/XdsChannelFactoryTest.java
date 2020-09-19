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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.XdsChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link XdsChannelFactory}.
 */
@RunWith(JUnit4.class)
public class XdsChannelFactoryTest {

  private final XdsChannelFactory channelFactory = XdsChannelFactory.getInstance();
  private final List<XdsChannel> channels = new ArrayList<>();
  private ServerInfo server1;  // google_default
  private ServerInfo server2;  // plaintext, v3
  private ServerInfo server3;  // unsupported

  @Before
  public void setUp() {
    ChannelCreds googleDefault = new ChannelCreds("google_default", null);
    ChannelCreds insecure = new ChannelCreds("insecure", null);
    ChannelCreds unsupported = new ChannelCreds("unsupported", null);
    server1 = new ServerInfo("server1.com", Collections.singletonList(googleDefault),
        Collections.<String>emptyList());
    server2 = new ServerInfo("server2.com", Collections.singletonList(insecure),
        Collections.singletonList("xds_v3"));
    server3 = new ServerInfo("server4.com", Collections.singletonList(unsupported),
        Collections.<String>emptyList());
  }

  @After
  public void tearDown() {
    for (XdsChannel channel : channels) {
      channel.getManagedChannel().shutdown();
    }
  }

  @Test
  public void failToCreateChannel_unsupportedChannelCreds() {
    try {
      createChannel(server3);
      fail("Should have thrown");
    } catch (XdsInitializationException expected) {
    }
  }

  @Test
  public void defaultUseV2ProtocolL() throws XdsInitializationException {
    XdsChannel channel = createChannel(server1);
    assertThat(channel.isUseProtocolV3()).isFalse();
  }

  @Test
  public void supportServerFeature_v3Protocol() throws XdsInitializationException {
    boolean originalV3SupportEnvVar = XdsChannelFactory.experimentalV3SupportEnvVar;
    XdsChannelFactory.experimentalV3SupportEnvVar = true;
    try {
      XdsChannel channel = createChannel(server2);
      assertThat(channel.isUseProtocolV3()).isTrue();
    } finally {
      XdsChannelFactory.experimentalV3SupportEnvVar = originalV3SupportEnvVar;
    }
  }

  private XdsChannel createChannel(ServerInfo... servers) throws XdsInitializationException {
    XdsChannel channel = channelFactory.createChannel(Arrays.asList(servers));
    channels.add(channel);
    return channel;
  }
}
