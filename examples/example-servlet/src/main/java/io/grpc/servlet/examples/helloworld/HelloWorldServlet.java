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

package io.grpc.servlet.examples.helloworld;

import io.grpc.stub.StreamObserver;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.servlet.ServletAdapter;
import io.grpc.servlet.ServletServerBuilder;
import java.io.IOException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A servlet that hosts a gRPC server over HTTP/2 and shares the resource URI for the normal servlet
 * clients over HTTP/1.0+.
 *
 * <p>For creating a servlet that solely serves gRPC services, do not follow this example, simply
 * extend or register a {@link io.grpc.servlet.GrpcServlet} instead.
 */
@WebServlet(urlPatterns = {"/helloworld.Greeter/SayHello"}, asyncSupported = true)
public class HelloWorldServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final ServletAdapter servletAdapter =
      new ServletServerBuilder().addService(new GreeterImpl()).buildServletAdapter();

  private static final class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    GreeterImpl() {}

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setContentType("text/html");
    response.getWriter().println("<p>Hello World!</p>");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (ServletAdapter.isGrpc(request)) {
      servletAdapter.doPost(request, response);
    } else {
      response.setContentType("text/html");
      response.getWriter().println("<p>Hello non-gRPC client!</p>");
    }
  }

  @Override
  public void destroy() {
    servletAdapter.destroy();
    super.destroy();
  }
}
