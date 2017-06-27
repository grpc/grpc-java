/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc.inprocess;

import com.google.common.base.Preconditions;
import io.grpc.ExperimentalApi;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.AbstractServerImplBuilder;
import java.io.File;
import java.util.List;

/**
 * Builder for a server that services in-process requests. Clients identify the in-process server by
 * its name.
 *
 * <p>The server is intended to be fully-featured, high performance, and useful in testing.
 *
 * <h3>Using JUnit TestRule</h3>
 * The class "GrpcServerRule" (from the "grpc-java/testing") is a JUnit TestRule that
 * creates a {@link InProcessServer} and a {@link io.grpc.ManagedChannel ManagedChannel}. This
 * test rule contains the boiler plate code shown below. The classes "HelloWorldServerTest" and
 * "HelloWorldClientTest" (from "grpc-java/examples") demonstrate basic usage.
 *
 * <h3>Usage example</h3>
 * <h4>Server and client channel setup</h4>
 * <pre>
 *   MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
 *   addServicesHelper(serviceRegistry); // your code here
 *   ExecutorService serverExecutor = Executors.newCachedThreadPool();
 *   Server server = InProcessServerBuilder.forName("unique-name")
 *       .executor(serverExecutor)
 *       .fallbackHandlerRegistry(serviceRegistry)
 *       .build().start();
 *   ExecutorService clientExecutor = Executors.newCachedThreadPool();
 *   ManagedChannel channel = InProcessChannelBuilder.forName("unique-name")
 *       .executor(clientExecutor)
 *       .build();
 * </pre>
 *
 * <h4>Client call usage</h4>
 * <pre>
 *   MethodDescriptor&lt;Integer, Integer&gt; methodDescriptor =
 *       makeMethodDescriptorHelper(); // your code here
 *   ClientCall&lt;Integer, Integer&gt; clientCall = channel.newCall(methodDescriptor,
 *       CallOptions.DEFAULT);
 * </pre>
 *
 * <h4>Stub usage</h4>
 * All stub variants are supported:
 * <pre>
 *   TestServiceGrpc.TestServiceBlockingStub blockingStub =
 *       TestServiceGrpc.newBlockingStub(channel);
 *   TestServiceGrpc.TestServiceStub asyncStub =
 *       TestServiceGrpc.newStub(channel);
 *   TestServiceGrpc.TestServiceFutureStub futureStub =
 *       TestServiceGrpc.newFutureStub(channel);
 * </pre>
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1783")
public final class InProcessServerBuilder
    extends AbstractServerImplBuilder<InProcessServerBuilder> {
  /**
   * Create a server builder that will bind with the given name.
   *
   * @param name the identity of the server for clients to connect to
   * @return a new builder
   */
  public static InProcessServerBuilder forName(String name) {
    return new InProcessServerBuilder(name);
  }

  private final String name;

  private InProcessServerBuilder(String name) {
    this.name = Preconditions.checkNotNull(name, "name");
  }

  @Override
  protected InProcessServer buildTransportServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    // TODO(zhangkun83): InProcessTransport by-passes framer and deframer, thus message sizses are
    // not counted.  Therefore, we disable stats for now.
    // (https://github.com/grpc/grpc-java/issues/2284)
    return new InProcessServer(name);
  }

  @Override
  public InProcessServerBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in InProcessServer");
  }
}
