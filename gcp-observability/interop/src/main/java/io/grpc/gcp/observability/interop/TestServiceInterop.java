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

package io.grpc.gcp.observability.interop;

import io.grpc.gcp.observability.GcpObservability;
import io.grpc.testing.integration.TestServiceClient;
import io.grpc.testing.integration.TestServiceServer;
import java.util.Arrays;

/**
 * Combined interop client and server for observability testing.
 */
public final class TestServiceInterop {
  public static void main(String[] args) throws Exception {
    boolean client;
    if (args.length < 1) {
      usage();
      return;
    }
    if ("server".equals(args[0])) {
      client = false;
    } else if ("client".equals(args[0])) {
      client = true;
    } else {
      usage();
      return;
    }
    args = Arrays.asList(args).subList(1, args.length).toArray(new String[0]);
    try (GcpObservability gcpObservability = GcpObservability.grpcInit()) {
      if (client) {
        TestServiceClient.main(args);
      } else {
        TestServiceServer.main(args);
      }
    }
  }

  private static void usage() {
    System.out.println("Usage: client|server [ARGS...]");
    System.exit(1);
  }
}
