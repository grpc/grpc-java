/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;

import java.util.logging.Level;
import java.util.logging.Logger;

public class XdsInteropTest {
  private static final Logger logger = Logger.getLogger(XdsInteropTest.class.getName());

  /**
   * The main application to run test cases.
   */
  public static void main(String[] args) throws Exception {
    AbstractXdsInteropTest testCase = new PingPong();
    testCase.setUp();
    try {
      testCase.run();
    } finally {
      testCase.tearDown();
    }
  }

  private static class PingPong extends AbstractXdsInteropTest {
    @Override
    void run() {
      SimpleRequest request = SimpleRequest.newBuilder()
          .build();
      SimpleResponse goldenResponse = SimpleResponse.newBuilder()
          .setResponseMessage("Hi, xDS!")
          .build();
      assertEquals(goldenResponse, blockingStub.unaryRpc(request));
      logger.log(Level.INFO, "success");
    }
  }
}
