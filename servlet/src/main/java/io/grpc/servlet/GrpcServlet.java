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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.BindableService;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A simple servlet backed by a gRPC server. Must set {@code asyncSupported} to true. The {@code
 * /contextRoot/urlPattern} must match the gRPC services' path, which is
 * "/full-service-name/short-method-name".
 *
 * <p>The API is unstable. The authors would like to know more about the real usecases. Users are
 * welcome to provide feedback by commenting on
 * <a href=https://github.com/grpc/grpc-java/issues/5066>the tracking issue</a>.
 */
@io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/5066")
public class GrpcServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final ServletAdapter servletAdapter;

  @VisibleForTesting
  GrpcServlet(ServletAdapter servletAdapter) {
    this.servletAdapter = servletAdapter;
  }

  /**
   * Instantiate the servlet with the given serverBuilder.
   */
  public GrpcServlet(ServletServerBuilder serverBuilder) {
    this(ServletAdapter.Factory.create(serverBuilder));
  }

  /**
   * Instantiate the servlet serving the given list of gRPC services.
   */
  public GrpcServlet(List<BindableService> grpcServices) {
    this(
        ((Supplier<ServletServerBuilder>)
                () -> {
                  ServletServerBuilder serverBuilder = new ServletServerBuilder();
                  grpcServices.forEach(service -> serverBuilder.addService(service));
                  return serverBuilder;
                })
            .get());
  }

  @Override
  protected final void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    servletAdapter.doGet(request, response);
  }

  @Override
  protected final void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    servletAdapter.doPost(request, response);
  }

  @Override
  public void destroy() {
    servletAdapter.destroy();
    super.destroy();
  }
}
