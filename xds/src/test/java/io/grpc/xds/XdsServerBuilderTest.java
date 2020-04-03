/*
 * Copyright 2019 The gRPC Authors
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Server;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsProtocolNegotiator;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsServerBuilder}.
 */
@RunWith(JUnit4.class)
public class XdsServerBuilderTest {

  @Test
  public void buildsXdsServerBuilder() {
    XdsServerBuilder builder = XdsServerBuilder.forPort(8080);
    assertThat(builder).isInstanceOf(XdsServerBuilder.class);
    Server server = builder.build();
    assertThat(server).isNotNull();
  }

  @Test
  public void xdsServer_callsShutdown() throws IOException, InterruptedException {
    XdsServerBuilder builder = XdsServerBuilder.forPort(8080);
    XdsClient mockXdsClient = mock(XdsClient.class);
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        new XdsClientWrapperForServerSds(8080, mockXdsClient, null);
    ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new ServerSdsProtocolNegotiator(null, xdsClientWrapperForServerSds);
    Server xdsServer = builder.buildServer(serverSdsProtocolNegotiator);
    xdsServer.start();
    xdsServer.shutdown();
    xdsServer.awaitTermination(500L, TimeUnit.MILLISECONDS);
    verify(mockXdsClient, times(1)).shutdown();
  }
}
