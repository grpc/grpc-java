/*
 * Copyright 2025 The gRPC Authors
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChildChannelConfigurersTest {

  @Test
  public void noOp_doesNothing() {
    ManagedChannelBuilder<?> builder = mock(ManagedChannelBuilder.class);
    ChildChannelConfigurers.noOp().accept(builder);
    verifyNoInteractions(builder);
  }

  @Test
  public void compose_runsInOrder() {
    ManagedChannelBuilder<?> builder = mock(ManagedChannelBuilder.class);
    ChildChannelConfigurer configurer1 = b -> b.userAgent("agent1");
    ChildChannelConfigurer configurer2 = b -> b.maxInboundMessageSize(123);

    ChildChannelConfigurers.compose(configurer1, configurer2).accept(builder);

    verify(builder).userAgent("agent1");
    verify(builder).maxInboundMessageSize(123);
  }

  @Test
  public void compose_ignoresNulls() {
    ManagedChannelBuilder<?> builder = mock(ManagedChannelBuilder.class);
    ChildChannelConfigurer configurer = b -> b.userAgent("agent1");

    ChildChannelConfigurers.compose(null, configurer, null).accept(builder);

    verify(builder).userAgent("agent1");
  }
}
