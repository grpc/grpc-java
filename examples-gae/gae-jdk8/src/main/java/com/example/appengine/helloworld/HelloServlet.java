/**
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.appengine.helloworld;

import static io.grpc.testing.integration.Messages.SimpleRequest;
import static io.grpc.testing.integration.Messages.SimpleResponse;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.testing.integration.TestServiceGrpc;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This servlet communicates with {@code grpc-test.sandbox.googleapis.com}, which is a server
 * managed by the gRPC team. For more information, see
 * <a href="https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md">
 *   Interoperability Test Case Descriptions</a>.
 */
@SuppressWarnings("serial")
public final class HelloServlet extends HttpServlet {
  // For GAE+jdk8: creating a long lived channel is supported.
  private final ManagedChannel channel;

  public HelloServlet() {
    channel = ManagedChannelBuilder.forAddress("grpc-test.sandbox.googleapis.com", 443).build();
  }

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    int desiredResponseSize = 12345;
    TestServiceGrpc.TestServiceBlockingStub blockingStub = TestServiceGrpc.newBlockingStub(channel);
    SimpleResponse simpleResponse = blockingStub.unaryCall(
        SimpleRequest.newBuilder().setResponseSize(desiredResponseSize).build());
    resp.setContentType("text/plain");
    int responseSize = simpleResponse.getPayload().getBody().size();
    if (responseSize == desiredResponseSize) {
      resp.getWriter().println("jdk8: success");
    } else {
      resp.getWriter().println(
          String.format(
              "failed, expected response of size %s but was %s",
              desiredResponseSize,
              responseSize));
    }
  }

}
