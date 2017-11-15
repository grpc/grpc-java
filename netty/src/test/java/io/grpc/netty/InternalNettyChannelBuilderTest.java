/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import io.grpc.netty.InternalNettyChannelBuilder.TestAccessor;
import io.grpc.netty.InternalNettyChannelBuilder.TransportCreationParamsFilterFactory;
import java.net.SocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link InternalNettyChannelBuilder}.
 */
@RunWith(JUnit4.class)
public class InternalNettyChannelBuilderTest {
  @Test
  public void getTcpfFactory() {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(new SocketAddress() {});

    assertEquals(null, TestAccessor.getDynamicTransportParamsFactory(builder));

    TransportCreationParamsFilterFactory factory1 =
        mock(TransportCreationParamsFilterFactory.class);
    InternalNettyChannelBuilder.setDynamicTransportParamsFactory(builder, factory1);
    assertEquals(factory1, TestAccessor.getDynamicTransportParamsFactory(builder));

    TransportCreationParamsFilterFactory factory2 =
        mock(TransportCreationParamsFilterFactory.class);
    InternalNettyChannelBuilder.setDynamicTransportParamsFactory(builder, factory2);
    assertEquals(factory2, TestAccessor.getDynamicTransportParamsFactory(builder));

    builder.build();
    assertEquals(factory2, TestAccessor.getDynamicTransportParamsFactory(builder));
  }
}
