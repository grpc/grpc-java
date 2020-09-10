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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import io.grpc.ServerStreamTracer;
import io.grpc.internal.ServerImplBuilder.ClientTransportServersBuilder;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ServerImplBuilder}. */
@RunWith(JUnit4.class)
public class ServerImplBuilderTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ClientTransportServersBuilder mockClientTransportServersBuilder;
  @Mock private List<? extends ServerStreamTracer.Factory> mockServerStreamTracerFactories;
  @Mock private List<? extends InternalServer> mockInternalServers;
  private ServerImplBuilder builder;

  @Before
  public void setUp() throws Exception {
    builder = new ServerImplBuilder(mockClientTransportServersBuilder);
  }

  @Test
  public void buildTransportServers() {
    doReturn(mockInternalServers).when(mockClientTransportServersBuilder)
        .buildClientTransportServers(ArgumentMatchers.<ServerStreamTracer.Factory>anyList());

    List<? extends InternalServer> servers = builder
        .buildTransportServers(mockServerStreamTracerFactories);
    assertEquals(mockInternalServers, servers);
    assertNotNull(servers);
    verify(mockClientTransportServersBuilder)
        .buildClientTransportServers(mockServerStreamTracerFactories);
  }
}
