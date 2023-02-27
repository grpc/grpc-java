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

import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.testing.integration.AbstractInteropTest;
import java.io.File;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Interop test for Tomcat server and Netty client.
 */
public class TomcatInteropTest extends AbstractInteropTest {

  private static final String HOST = "localhost";
  private static final String MYAPP = "/grpc.testing.TestService";
  private int port;
  private Tomcat server;

  @After
  @Override
  public void tearDown() {
    super.tearDown();
    try {
      server.stop();
    } catch (LifecycleException e) {
      throw new AssertionError(e);
    }
  }

  @AfterClass
  public static void cleanUp() throws Exception {
    FileUtils.deleteDirectory(new File("tomcat.0"));
  }

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    return new ServletServerBuilder().maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
  }

  @Override
  protected void startServer(ServerBuilder<?> builer) {
    server = new Tomcat();
    server.setPort(0);
    Context ctx = server.addContext(MYAPP, new File("build/tmp").getAbsolutePath());
    Tomcat
        .addServlet(
            ctx, "io.grpc.servlet.TomcatInteropTest",
            new GrpcServlet(((ServletServerBuilder) builer).buildServletAdapter()))
        .setAsyncSupported(true);
    ctx.addServletMappingDecoded("/*", "io.grpc.servlet.TomcatInteropTest");

    // Explicitly disable safeguards against malicious clients, as some unit tests trigger these
    Http2Protocol http2Protocol = new Http2Protocol();
    http2Protocol.setOverheadCountFactor(0);
    http2Protocol.setOverheadWindowUpdateThreshold(0);
    http2Protocol.setOverheadContinuationThreshold(0);
    http2Protocol.setOverheadDataThreshold(0);

    server.getConnector().addUpgradeProtocol(http2Protocol);
    try {
      server.start();
    } catch (LifecycleException e) {
      throw new RuntimeException(e);
    }

    port = server.getConnector().getLocalPort();
  }

  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder() {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(HOST, port)
                                  .usePlaintext()
                                  .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
    InternalNettyChannelBuilder.setStatsEnabled(builder, false);
    builder.intercept(createCensusStatsClientInterceptor());
    return builder;
  }

  @Override
  protected boolean metricsExpected() {
    return false; // otherwise re-test will not work
  }

  // FIXME
  @Override
  @Ignore("Tomcat is broken on client GOAWAY")
  @Test
  public void gracefulShutdown() {}

  // FIXME
  @Override
  @Ignore("Tomcat is not able to send trailer only")
  @Test
  public void specialStatusMessage() {}

  // FIXME
  @Override
  @Ignore("Tomcat is not able to send trailer only")
  @Test
  public void unimplementedMethod() {}

  // FIXME
  @Override
  @Ignore("Tomcat is not able to send trailer only")
  @Test
  public void statusCodeAndMessage() {}

  // FIXME
  @Override
  @Ignore("Tomcat is not able to send trailer only")
  @Test
  public void emptyStream() {}
}
