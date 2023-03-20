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
import io.grpc.ExperimentalApi;
import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A simple servlet backed by a gRPC server. Must set {@code asyncSupported} to true. The {@code
 * /contextRoot/urlPattern} must match the gRPC services' path, which is
 * "/full-service-name/short-method-name".
 *
 * <p>The API is experimental. The authors would like to know more about the real usecases. Users
 * are welcome to provide feedback by commenting on
 * <a href=https://github.com/grpc/grpc-java/issues/5066>the tracking issue</a>.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5066")
public class GrpcServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final ServletAdapter servletAdapter;

  @VisibleForTesting
  GrpcServlet(ServletAdapter servletAdapter) {
    this.servletAdapter = servletAdapter;
  }

  /**
   * Instantiate the servlet serving the given list of gRPC services. ServerInterceptors can be
   * added on each gRPC service by {@link
   * io.grpc.ServerInterceptors#intercept(BindableService, io.grpc.ServerInterceptor...)}
   */
  public GrpcServlet(List<? extends BindableService> bindableServices) {
    this(loadServices(bindableServices));
  }

  private static ServletAdapter loadServices(List<? extends BindableService> bindableServices) {
    ServletServerBuilder serverBuilder = new ServletServerBuilder();
    bindableServices.forEach(serverBuilder::addService);
    return serverBuilder.buildServletAdapter();
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
