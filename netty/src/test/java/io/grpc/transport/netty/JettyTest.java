package io.grpc.transport.netty;

import static io.grpc.MethodType.UNARY;
import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.Call;
import io.grpc.ChannelImpl;
import io.grpc.Marshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerImpl;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.Calls;
import io.grpc.testing.TestUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.ssl.SslContext;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Verifies that the Netty client works properly in the Jetty container when using TLS with ALPN.
 */
@RunWith(JUnit4.class)
public class JettyTest {
  private static final String TESTCA_HOST = "foo.test.google.fr";
  private static final String GRPC_SERVER_PORT_ATTRIBUTE = "grpcServerPort";
  private static final String GRPC_SERVICE_NAME = "jettyTest";
  private static final String GRPC_METHOD_NAME = "unary";

  private org.eclipse.jetty.server.Server jettyServer;
  private ServerImpl grpcServer;
  private URL url;

  @Before
  public void setup() throws Exception {
    // Build and start the gRPC server.
    int grpcServerPort = TestUtils.pickUnusedPort();
    File cert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    SslContext sslContext = GrpcSslContexts.forServer(cert, key).build();
    NettyServerBuilder builder = NettyServerBuilder.forPort(grpcServerPort).sslContext(sslContext);
    grpcServer = builder.addService(ServerServiceDefinition.builder(GRPC_SERVICE_NAME).addMethod(
            GRPC_METHOD_NAME,
            new ByteBufMarshaller(),
            new ByteBufMarshaller(),
            new CallHandler()).build()).build();
    grpcServer.start();

    // Start the jetty server.
    jettyServer = new org.eclipse.jetty.server.Server();
    ServerConnector connector = new ServerConnector(jettyServer);
    connector.setPort(TestUtils.pickUnusedPort());
    jettyServer.setConnectors(new Connector[]{connector});
    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    context.addServlet(TestServlet.class, "/test");
    context.setAttribute(GRPC_SERVER_PORT_ATTRIBUTE, grpcServerPort);
    HandlerCollection handlers = new HandlerCollection();
    handlers.setHandlers(new Handler[]{context, new DefaultHandler()});
    jettyServer.setHandler(handlers);
    jettyServer.start();

    url = new URL("http://localhost:" + connector.getPort() + "/test");
  }

  @After
  public void teardown() throws Exception {
    grpcServer.shutdownNow();
    jettyServer.stop();
  }

  @Test
  public void grpcClientWithinJettyShouldBeAbleToUseAlpn() throws Exception {
    // Just fire off a GET to trigger a gRPC request from within the Jetty container.
    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    try {
      InputStream in = urlConnection.getInputStream();
      drain(in);
    } finally {
      urlConnection.disconnect();
    }
  }

  private static void drain(InputStream in) throws IOException {
    byte[] buf = new byte[1024];
    while ((in.read(buf)) >= 0) {
      // Just keep reading.
    }
  }

  /**
   * Simple servlet which, upon receiving a GET, just makes a gRPC request and waits for the
   * response.
   */
  public static class TestServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
      ChannelImpl channel = newClientChannel();
      try {
        String name = GRPC_SERVICE_NAME + "/" + GRPC_METHOD_NAME;
        MethodDescriptor<ByteBuf, ByteBuf> method = MethodDescriptor.create(UNARY,
                name, 10, SECONDS, new ByteBufMarshaller(), new ByteBufMarshaller());
        Call<ByteBuf, ByteBuf> call = channel.newCall(method);
        ListenableFuture<ByteBuf> future = Calls.unaryFutureCall(call, EMPTY_BUFFER);
        future.get(10, SECONDS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        channel.shutdownNow();
      }
    }

    private ChannelImpl newClientChannel() throws IOException {
      int grpcServerPort = (Integer) getServletContext().getAttribute(GRPC_SERVER_PORT_ATTRIBUTE);

      InetAddress inetAddress = InetAddress.getByName("localhost");
      inetAddress = InetAddress.getByAddress(TESTCA_HOST, inetAddress.getAddress());
      InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, grpcServerPort);

      File cert = TestUtils.loadCert("ca.pem");
      System.err.println("client cert file: " + cert + " exists: " + cert.exists());
      SslContext context = GrpcSslContexts.forClient().trustManager(cert).build();

      return NettyChannelBuilder
              .forAddress(socketAddress)
              .negotiationType(NegotiationType.TLS)
              .sslContext(context)
              .build();
    }
  }

  /**
   * Simple server side handler for gRPC requests that just immediately responds with an
   * empty payload.
   */
  private static class CallHandler implements ServerCallHandler<ByteBuf, ByteBuf> {
    @Override
    public ServerCall.Listener<ByteBuf> startCall(String fullMethodName,
                                                  final ServerCall<ByteBuf> call,
                                                  Metadata.Headers headers) {
      call.request(1);
      return new ServerCall.Listener<ByteBuf>() {
        @Override
        public void onPayload(ByteBuf payload) {
          // no-op
          payload.release();
          call.sendPayload(EMPTY_BUFFER);
        }

        @Override
        public void onHalfClose() {
          call.close(Status.OK, new Metadata.Trailers());
        }

        @Override
        public void onCancel() {
        }

        @Override
        public void onComplete() {
        }
      };
    }
  }

  /**
   * Simple {@link io.grpc.Marshaller} for Netty ByteBuf.
   */
  protected static class ByteBufMarshaller implements Marshaller<ByteBuf> {
    protected ByteBufMarshaller() {
    }

    @Override
    public InputStream stream(ByteBuf value) {
      return new ByteBufInputStream(value);
    }

    @Override
    public ByteBuf parse(InputStream stream) {
      try {
        // We don't do anything with the payload and it's already been read into buffers
        // so just skip copying it.
        int available = stream.available();
        if (stream.skip(available) != available) {
          throw new RuntimeException("Unable to skip available bytes.");
        }
        return EMPTY_BUFFER;
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
  }
}