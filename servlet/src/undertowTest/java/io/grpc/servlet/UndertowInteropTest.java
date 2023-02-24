/*
 * Copyright 2018 The gRPC Authors
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

import static io.undertow.servlet.Servlets.defaultContainer;
import static io.undertow.servlet.Servlets.deployment;
import static io.undertow.servlet.Servlets.servlet;

import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.testing.integration.AbstractInteropTest;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.util.ImmediateInstanceHandle;
import java.net.InetSocketAddress;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Interop test for Undertow server and Netty client.
 */
public class UndertowInteropTest extends AbstractInteropTest {
  private static final String HOST = "localhost";
  private static final String MYAPP = "/grpc.testing.TestService";
  private int port;
  private Undertow server;
  private DeploymentManager manager;

  @After
  @Override
  public void tearDown() {
    super.tearDown();
    if (server != null) {
      server.stop();
    }
    if (manager != null) {
      try {
        manager.stop();
      } catch (ServletException e) {
        throw new AssertionError("failed to stop container", e);
      }
    }
  }

  @Override
  protected ServletServerBuilder getServerBuilder() {
    return new ServletServerBuilder().maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
  }

  @Override
  protected void startServer(ServerBuilder<?> builder) {
    GrpcServlet grpcServlet =
        new GrpcServlet(((ServletServerBuilder) builder).buildServletAdapter());
    InstanceFactory<? extends Servlet> instanceFactory =
        () -> new ImmediateInstanceHandle<>(grpcServlet);
    DeploymentInfo servletBuilder =
        deployment()
            .setClassLoader(UndertowInteropTest.class.getClassLoader())
            .setContextPath(MYAPP)
            .setDeploymentName("UndertowInteropTest.war")
            .addServlets(
                servlet("InteropTestServlet", GrpcServlet.class, instanceFactory)
                    .addMapping("/*")
                    .setAsyncSupported(true));

    manager = defaultContainer().addDeployment(servletBuilder);
    manager.deploy();

    HttpHandler servletHandler;
    try {
      servletHandler = manager.start();
    } catch (ServletException e) {
      throw new RuntimeException(e);
    }
    PathHandler path = Handlers.path(Handlers.redirect(MYAPP))
        .addPrefixPath("/", servletHandler); // for unimplementedService test
    server = Undertow.builder()
        .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
        .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 5000 /* 5 sec */)
        .addHttpListener(0, HOST)
        .setHandler(path)
        .build();
    server.start();
    port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
  }

  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder() {
    NettyChannelBuilder builder = NettyChannelBuilder
            .forAddress(HOST, port)
            .usePlaintext()
            .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
    InternalNettyChannelBuilder.setStatsEnabled(builder, false);
    builder.intercept(createCensusStatsClientInterceptor());
    return builder;
  }

  // FIXME
  @Override
  @Ignore("Undertow is broken on client GOAWAY")
  @Test
  public void gracefulShutdown() {}
}
