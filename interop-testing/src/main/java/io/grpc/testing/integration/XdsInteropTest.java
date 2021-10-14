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

package io.grpc.testing.integration;

import static io.grpc.testing.integration.AbstractInteropTest.EMPTY;
import static org.junit.Assert.assertEquals;

import java.util.logging.Level;
import java.util.logging.Logger;

public class XdsInteropTest {
  private final static Logger logger = Logger.getLogger(XdsInteropTest.class.getName());

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
      TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);
      assertEquals(EMPTY, stub.emptyCall(EmptyProtos.Empty.getDefaultInstance()));
      logger.log(Level.INFO, "success");
    }
  }
}