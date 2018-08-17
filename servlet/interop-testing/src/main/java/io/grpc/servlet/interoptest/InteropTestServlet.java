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

package io.grpc.servlet.interoptest;

import io.grpc.servlet.GrpcServlet;
import io.grpc.testing.integration.TestServiceImpl;
import java.util.Collections;
import java.util.concurrent.Executors;
import javax.servlet.annotation.WebServlet;

/** A servlet that hosts a gRPC server. */
@WebServlet(urlPatterns = "/grpc.testing.TestService/*", asyncSupported = true)
public class InteropTestServlet extends GrpcServlet {
  private static final long serialVersionUID = 1L;

  public InteropTestServlet() {
    super(Collections.singletonList(new TestServiceImpl(Executors.newScheduledThreadPool(2))));
  }
}
