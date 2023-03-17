/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.debug;

import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Greeter implementation which replies identifying itself with its hostname. */
public final class HostnameGreeter extends GreeterGrpc.GreeterImplBase {
  private static final Logger logger = Logger.getLogger(HostnameGreeter.class.getName());

  private AtomicInteger callCount = new AtomicInteger();

  private final String serverName;

  public HostnameGreeter(String serverName) {
    if (serverName == null) {
      serverName = determineHostname();
    }
    this.serverName = serverName;
  }

  @Override
  public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
    int curCount = callCount.incrementAndGet();
    HelloReply reply = HelloReply.newBuilder()
        .setMessage(String.format("Hello %s, from %s.  You are requester number %d.",
                req.getName(), serverName, curCount))
        .build();
    // Add a pause so that there is time to run debug commands
    try {
      int sleep_interval = (curCount % 10) * 100; // 0 - 1 second
      Thread.sleep(sleep_interval);
    } catch (InterruptedException e) {
      responseObserver.onError(e);
    }
    // Send the response
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }

  private static String determineHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException ex) {
      logger.log(Level.INFO, "Failed to determine hostname. Will generate one", ex);
    }
    // Strange. Well, let's make an identifier for ourselves.
    return "generated-" + new Random().nextInt();
  }
}
