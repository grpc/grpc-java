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

package io.grpc.protobuf.lite;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import io.grpc.protobuf.Messages.TestMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProtoInputStream}. */
@RunWith(JUnit4.class)
public class ProtoInputStreamTest {
  private final TestMessage message = TestMessage.newBuilder()
        .setB(true)
        .setI(12345)
        .setS("a very very very very very very very very very very very very very long string")
        .setBs(ByteString.copyFromUtf8("byte string"))
        .build();
  private final byte[] serializedMessage = message.toByteArray();

  @Test
  public void teeEntireMessage() throws Exception {
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    is.mark(Integer.MAX_VALUE);
    assertTrue(Arrays.equals(serializedMessage, ByteStreams.toByteArray(is)));
    is.reset();
    assertTrue(Arrays.equals(serializedMessage, ByteStreams.toByteArray(is)));
  }

  @Test
  public void teeStream_beginning() throws Exception {
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    is.mark(Integer.MAX_VALUE);
    byte[] partial = new byte[40];
    assertEquals(partial.length, is.read(partial, 0, partial.length));
    assertEquals(
        ByteBuffer.wrap(serializedMessage).limit(partial.length),
        ByteBuffer.wrap(partial));
    is.reset();
    assertTrue(Arrays.equals(serializedMessage, ByteStreams.toByteArray(is)));
  }

  @Test
  public void teeStream_middle() throws Exception {
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    byte[] throwAway = new byte[10];
    assertEquals(throwAway.length, is.read(throwAway));
    is.mark(Integer.MAX_VALUE);
    byte[] partial = new byte[40];
    assertEquals(partial.length, is.read(partial, 0, partial.length));
    assertEquals(
        ByteBuffer.wrap(serializedMessage)
            .position(throwAway.length)
            .limit(throwAway.length + partial.length),
        ByteBuffer.wrap(partial));
    byte[] remaining = ByteStreams.toByteArray(is);
    assertEquals(
        ByteBuffer.wrap(serializedMessage)
            .position(throwAway.length + partial.length),
        ByteBuffer.wrap(remaining));
  }

  @Test
  public void teeStream_end() throws Exception {
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    assertTrue(Arrays.equals(serializedMessage, ByteStreams.toByteArray(is)));
    is.mark(Integer.MAX_VALUE);
    byte[] bytes = new byte[1];
    assertEquals(-1, is.read(bytes, 0, 1));
    is.reset();
    assertEquals(-1, is.read(bytes, 0, 1));
  }

  @Test
  public void teeStream_end_bulkDrained() throws Exception {
    // ProtoInputStream avoids creating an internal buffer in this case. mark() normally
    // relies on the existence of the buffer, but since there's no data left to
    // read it doesn't matter.
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    is.drainTo(baos);
    is.mark(Integer.MAX_VALUE);
    byte[] bytes = new byte[1];
    assertEquals(-1, is.read(bytes, 0, 1));
    is.reset();
    assertEquals(-1, is.read(bytes, 0, 1));
  }

  @Test(expected = IOException.class)
  public void resetWithoutMark() throws Exception {
    ProtoInputStream is = new ProtoInputStream(message, message.getParserForType());
    is.reset();
  }
}
