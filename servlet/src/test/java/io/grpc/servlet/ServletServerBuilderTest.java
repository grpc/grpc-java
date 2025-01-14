/*
 * Copyright 2022 The gRPC Authors
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.Enumeration;
import java.util.StringTokenizer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for {@link ServletServerBuilder}. */
@RunWith(JUnit4.class)
public class ServletServerBuilderTest {

  @Test
  public void scheduledExecutorService() throws Exception {
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    AsyncContext asyncContext = mock(AsyncContext.class);
    ServletInputStream inputStream = mock(ServletInputStream.class);
    ServletOutputStream outputStream = mock(ServletOutputStream.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);

    doReturn(future).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    doReturn(true).when(request).isAsyncSupported();
    doReturn(asyncContext).when(request).startAsync(request, response);
    doReturn("application/grpc").when(request).getContentType();
    doReturn("/hello/world").when(request).getRequestURI();
    @SuppressWarnings({"JdkObsolete", "unchecked"}) // Required by servlet API signatures.
    // StringTokenizer is actually Enumeration<String>
    Enumeration<String> headerNames =
        (Enumeration<String>) ((Enumeration<?>) new StringTokenizer("grpc-timeout"));
    @SuppressWarnings({"JdkObsolete", "unchecked"})
    Enumeration<String> headers =
        (Enumeration<String>) ((Enumeration<?>) new StringTokenizer("1m"));
    doReturn(headerNames).when(request).getHeaderNames();
    doReturn(headers).when(request).getHeaders("grpc-timeout");
    doReturn(new StringBuffer("localhost:8080")).when(request).getRequestURL();
    doReturn(inputStream).when(request).getInputStream();
    doReturn("1.1.1.1").when(request).getLocalAddr();
    doReturn(8080).when(request).getLocalPort();
    doReturn("remote").when(request).getRemoteHost();
    doReturn(80).when(request).getRemotePort();
    doReturn(outputStream).when(response).getOutputStream();
    doReturn(request).when(asyncContext).getRequest();
    doReturn(response).when(asyncContext).getResponse();

    ServletServerBuilder serverBuilder =
        new ServletServerBuilder().scheduledExecutorService(scheduler);
    ServletAdapter servletAdapter = serverBuilder.buildServletAdapter();
    servletAdapter.doPost(request, response);

    verify(asyncContext).setTimeout(1);

    // The following just verifies that scheduler is populated to the transport.
    // It doesn't matter what tasks (such as handshake timeout and request deadline) are actually
    // scheduled.
    verify(scheduler, timeout(5000).atLeastOnce())
        .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
  }
}
