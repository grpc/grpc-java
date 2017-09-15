/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc;

import static org.junit.Assert.assertEquals;

import io.grpc.Grpc.Side;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Grpc}. */
@RunWith(JUnit4.class)
public class GrpcTest {

  @Test
  public void makeTraceSpanName() {
    assertEquals("Sent.io.grpc.Foo", Grpc.makeTraceSpanName(Side.CLIENT, "io.grpc/Foo"));
    assertEquals("Recv.io.grpc.Bar", Grpc.makeTraceSpanName(Side.SERVER, "io.grpc/Bar"));
  }
}
