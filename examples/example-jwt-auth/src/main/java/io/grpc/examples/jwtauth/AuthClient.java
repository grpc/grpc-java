/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.examples.jwtauth;

import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * An authenticating client that requests a greeting from the {@link AuthServer}.
 */
public class AuthClient {

  private static final Logger logger = Logger.getLogger(AuthClient.class.getName());

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /**
   * Construct client for accessing GreeterGrpc server.
   */
  AuthClient(String host, int port) {
    this(ManagedChannelBuilder
        .forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For this example we disable TLS to avoid
        // needing certificates, but it is recommended to use a secure channel while passing
        // credentials.
        .usePlaintext()
        .build());
  }

  /**
   * Construct client for accessing GreeterGrpc server using the existing channel.
   */
  AuthClient(ManagedChannel channel) {
    this.channel = channel;
    this.blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Say hello to server.
   *
   * @param name name to set in HelloRequest
   * @param clientId client identifier to set in JWT subject
   * @return the message in the HelloReply from the server
   */
  public String greet(String name, String clientId) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();

    String jwt = getJwt(clientId); // build JWT
    CallCredentials credentials = new BearerToken(jwt); // Wrap JWT in CallCredentials
    HelloReply response = blockingStub
        .withCallCredentials(credentials) // get a new stub that uses the given call credentials
        .sayHello(request);               // and call the server using it

    logger.info("Greeting: " + response.getMessage());
    return response.getMessage();
  }

  private static String getJwt(String clientId) {
    return Jwts.builder()
        .setSubject(clientId)
        .signWith(SignatureAlgorithm.HS256, Constant.JWT_SIGNING_KEY)
        .compact();
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the greeting
   * and the second is the client identifier to set in JWT
   */
  public static void main(String[] args) throws Exception {

    String host = "localhost";
    int port = 50051;
    String user = "world";
    String clientId = "default-client";

    if (args.length > 0) {
      host = args[0]; // Use the arg as the server host if provided
    }
    if (args.length > 1) {
      port = Integer.parseInt(args[1]); // Use the second argument as the server port if provided
    }
    if (args.length > 2) {
      user = args[2]; // Use the the third argument as the name to greet if provided
    }
    if (args.length > 3) {
      clientId = args[3]; // Use the fourth argument as the client identifier if provided
    }

    AuthClient client = new AuthClient(host, port);

    try {
      client.greet(user, clientId);
    } finally {
      client.shutdown();
    }
  }
}
