/*
 * Copyright 2025 The gRPC Authors
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

import static io.grpc.servlet.GrpcServlet.REMOVE_CONTEXT_PATH;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcServletTest {
  private static final String EXPECTED_METHOD = "hello/world";
  private static final String CONTEXT_PATH = "/grpc";
  private static final String REQUEST_URI = "/hello/world";

  @Test
  public void defaultMethodTest() throws ServletException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    GrpcServlet grpcServlet = mock(GrpcServlet.class);

    doCallRealMethod().when(grpcServlet).init();
    grpcServlet.init();

    doReturn(REQUEST_URI).when(request).getRequestURI();
    when(grpcServlet.getMethod(request)).thenCallRealMethod();

    assertEquals(EXPECTED_METHOD, grpcServlet.getMethod(request));
  }

  @Test
  public void removeContextPathMethodTest() throws ServletException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    GrpcServlet grpcServlet = mock(GrpcServlet.class);

    doReturn("true").when(grpcServlet).getInitParameter(REMOVE_CONTEXT_PATH);
    doCallRealMethod().when(grpcServlet).init();
    grpcServlet.init();

    doReturn(CONTEXT_PATH + REQUEST_URI).when(request).getRequestURI();
    doReturn(CONTEXT_PATH).when(request).getContextPath();
    when(grpcServlet.getMethod(request)).thenCallRealMethod();

    assertEquals(EXPECTED_METHOD, grpcServlet.getMethod(request));
  }
}
