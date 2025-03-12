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
import io.grpc.ExperimentalApi;
import java.io.IOException;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A simple servlet backed by a gRPC server. Must set {@code asyncSupported} to true. The {@code
 * /contextRoot/urlPattern} must match the gRPC services' path, which is
 * "/full-service-name/short-method-name".
 * If you use application server and want get access to grpc from path
 * "/deployment-name/full-service-name/short-method-name"
 * you must use {@link GrpcServlet#REMOVE_CONTEXT_PATH} for remove
 * context path("deployment-name") from method name.
 * <a href=https://github.com/grpc/grpc-java/pull/11825>More info</a>.
 *
 * <p>The API is experimental. The authors would like to know more about the real usecases. Users
 * are welcome to provide feedback by commenting on
 * <a href=https://github.com/grpc/grpc-java/issues/5066>the tracking issue</a>.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5066")
public class GrpcServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  public static final String REMOVE_CONTEXT_PATH = "REMOVE_CONTEXT_PATH";

  private final ServletAdapter servletAdapter;
  private boolean removeContextPath = false; // default value;

  GrpcServlet(ServletAdapter servletAdapter) {
    this.servletAdapter = servletAdapter;
  }

  @Override
  public void init() throws ServletException {
    super.init();
    removeContextPath = Boolean.parseBoolean(getInitParameter(REMOVE_CONTEXT_PATH));
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

  protected String getMethod(HttpServletRequest req) {
    String method = req.getRequestURI();
    if (removeContextPath) {
      // remove context path used in application server
      method = method.substring(req.getContextPath().length());
    }
    return method.substring(1); // remove the leading "/"
  }

  @Override
  protected final void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    servletAdapter.doGet(request, response);
  }

  @Override
  protected final void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    servletAdapter.doPost(getMethod(request), request, response);
  }

  @Override
  public void destroy() {
    servletAdapter.destroy();
    super.destroy();
  }
}
