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

import io.grpc.BindableService;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A simple servlet backed by a gRPC server. Must set {@code asyncSupported} to true. The {@code
 * /contextRoot/urlPattern} must match the gRPC services' path, which is
 * "/full-service-name/short-method-name".
 */
public class GrpcServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final ServletAdapter servletAdapter;

  /**
   * Instantiate the servlet serving the given list of gRPC services.
   */
  public GrpcServlet(List<BindableService> grpcServices) {
    ServletServerBuilder serverBuilder = new ServletServerBuilder();
    grpcServices.forEach(service -> serverBuilder.addService(service));
    servletAdapter = ServletAdapter.Factory.create(serverBuilder);
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
