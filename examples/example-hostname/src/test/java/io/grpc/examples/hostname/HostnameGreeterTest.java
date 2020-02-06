/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.examples.hostname;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.hostname.HostnameGreeter;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link HostnameGreeter}.
 *
 * <p>This is very similar to HelloWorldServerTest, so see it for more descriptions.
 */
@RunWith(JUnit4.class)
public class HostnameGreeterTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private GreeterGrpc.GreeterBlockingStub blockingStub =
        GreeterGrpc.newBlockingStub(grpcCleanup.register(
            InProcessChannelBuilder.forName("hostname").directExecutor().build()));

  @Test
  public void sayHello_fixedHostname() throws Exception {
    grpcCleanup.register(
        InProcessServerBuilder.forName("hostname")
          .directExecutor().addService(new HostnameGreeter("me")).build().start());

    HelloReply reply =
        blockingStub.sayHello(HelloRequest.newBuilder().setName("you").build());
    assertEquals("Hello you, from me", reply.getMessage());
  }

  @Test
  public void sayHello_dynamicHostname() throws Exception {
    grpcCleanup.register(
        InProcessServerBuilder.forName("hostname")
          .directExecutor().addService(new HostnameGreeter(null)).build().start());

    // Just verifing the service doesn't crash
    HelloReply reply =
        blockingStub.sayHello(HelloRequest.newBuilder().setName("anonymous").build());
    assertTrue(reply.getMessage(), reply.getMessage().startsWith("Hello anonymous, from "));
  }
}
