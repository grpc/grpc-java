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

package io.grpc.servlet;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.TestServiceGrpc;
import io.grpc.testing.integration.TestServiceImpl;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http2.parser.RateControl;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Smoke test for {@link GrpcServlet}. */
@RunWith(JUnit4.class)
public class GrpcServletSmokeTest {
  private static final String HOST = "localhost";
  private static final String MYAPP = "/grpc.testing.TestService";

  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private int port;
  private Server server;

  @Before
  public void startServer() {
    BindableService service = new TestServiceImpl(scheduledExecutorService);
    GrpcServlet grpcServlet = new GrpcServlet(ImmutableList.of(service));
    server = new Server(0);
    ServerConnector sc = (ServerConnector)server.getConnectors()[0];
    HTTP2CServerConnectionFactory factory =
        new HTTP2CServerConnectionFactory(new HttpConfiguration());

    // Explicitly disable safeguards against malicious clients, as some unit tests trigger this
    factory.setRateControlFactory(new RateControl.Factory() {});

    sc.addConnectionFactory(factory);
    ServletContextHandler context =
        new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath(MYAPP);
    context.addServlet(new ServletHolder(grpcServlet), "/*");
    server.setHandler(context);

    try {
      server.start();
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    port = sc.getLocalPort();
  }

  @After
  public void tearDown() {
    scheduledExecutorService.shutdown();
    try {
      server.stop();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void unaryCall() {
    Channel channel = cleanupRule.register(
        ManagedChannelBuilder.forAddress(HOST, port).usePlaintext().build());
    SimpleResponse response = TestServiceGrpc.newBlockingStub(channel).unaryCall(
        SimpleRequest.newBuilder()
            .setResponseSize(1234)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFromUtf8("hello foo")))
            .build());
    assertThat(response.getPayload().getBody().size()).isEqualTo(1234);
  }

  @Test
  public void httpGetRequest() throws Exception {
    HttpClient httpClient = new HttpClient();
    try {
      httpClient.start();
      ContentResponse response =
          httpClient.GET("http://" + HOST + ":" + port + MYAPP + "/UnaryCall");
      assertThat(response.getStatus()).isEqualTo(405);
      assertThat(response.getContentAsString()).contains("GET method not supported");
    } finally {
      httpClient.stop();
    }
  }
}
