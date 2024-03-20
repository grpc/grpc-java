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

package io.grpc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.ServerCall;
import io.grpc.internal.NoopServerCall;
import org.junit.Test;

public class SerializingServerCallTest {

  @Test
  public void testMethods() {
    ServerCall<Integer, Integer> testCall = new SerializingServerCall<>(new NoopServerCall<>());
    testCall.setCompression("gzip");
    testCall.setMessageCompression(true);
    assertTrue(testCall.isReady());
    assertFalse(testCall.isCancelled());
    assertNull(testCall.getAuthority());
    assertNotNull(testCall.getAttributes());
  }
}
