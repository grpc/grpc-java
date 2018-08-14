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

import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ServerTransportListener;
import java.io.IOException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * An adapter that transforms {@link HttpServletRequest} into gRPC request and lets a gRPC server
 * process it, and transforms the gRPC response into {@link HttpServletResponse}. An adapter can be
 * instantiated by {@link Factory#create}. The gRPC server is built from the ServerBuilder provided
 * in {@link Factory#create}.
 *
 * <p>In a servlet, calling {@link #doPost(HttpServletRequest, HttpServletResponse)} inside {@link
 * javax.servlet.http.HttpServlet#doPost(HttpServletRequest, HttpServletResponse)} makes the servlet
 * backed by the gRPC server associated with the adapter. The servlet must support Asynchronous
 * Processing and must be deployed to a container that supports servlet 4.0 and enables HTTP/2.
 */
public interface ServletAdapter {

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doPost(HttpServletRequest,
   * HttpServletResponse)} to serve gRPC POST request.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException;

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doGet(HttpServletRequest,
   * HttpServletResponse)} to serve gRPC GET request.
   *
   * <p>Note that in rare case gRPC client sends GET requests.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException;

  /**
   * Call this method before the adapter is in use.
   */
  @PostConstruct
  default void init() {}

  /**
   * Call this method when the adapter is no longer need.
   */
  @PreDestroy
  void destroy();

  /**
   * Checks whether an incoming {@code HttpServletRequest} may come from a gRPC client.
   *
   * @return true if the request comes from a gRPC client
   */
  static boolean isGrpc(HttpServletRequest request) {
    return request.getContentType() != null
        && request.getContentType().contains(GrpcUtil.CONTENT_TYPE_GRPC);
  }

  /** Factory of ServletAdapter. */
  final class Factory {

    /**
     * Creates an instance of ServletAdapter. A gRPC server will be built and started with the given
     * {@link ServletServerBuilder}. The servlet using this servletAdapter will be backed by the
     * gRPC server.
     */
    public static ServletAdapter create(ServletServerBuilder serverBuilder) {
      ServerTransportListener listener = serverBuilder.buildAndStart();
      return new ServletAdapterImpl(listener, serverBuilder.getScheduledExecutorService());
    }
  }
}
