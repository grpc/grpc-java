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

package io.grpc.servlet.examples.helloworld;

import io.grpc.stub.StreamObserver;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.servlet.ServerBuilder;
import io.grpc.servlet.ServletAdapter;
import java.util.concurrent.Executors;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

/**
 * A ManagedBean that produces an instance of ServletAdapter.
 */
@ApplicationScoped
final class ServletAdapterProvider {
  @Produces
  private ServletAdapter getServletAdapter() {
    return Provider.servletAdapter;
  }

  private static final class Provider {
    static final ServletAdapter servletAdapter = ServletAdapter.Factory.create(
        new ServerBuilder().addService(new GreeterImpl()));
  }

  private static final class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    GreeterImpl() {}

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
