/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Enum;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Type;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.GrpcUtil;
import io.grpc.testing.protobuf.SimpleRecursiveMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProtoLiteUtils}. */
@RunWith(JUnit4.class)
public class ProtoLiteUtilsTest {

  private final Marshaller<Type> marshaller = ProtoLiteUtils.marshaller(Type.getDefaultInstance());
  private Type proto = Type.newBuilder().setName("name").build();

  @Test
  public void testPassthrough() {
    assertSame(proto, marshaller.parse(marshaller.stream(proto)));
  }

  @Test
  public void testRoundtrip() throws Exception {
    InputStream is = marshaller.stream(proto);
    is = new ByteArrayInputStream(ByteStreams.toByteArray(is));
    assertEquals(proto, marshaller.parse(is));
  }

  @Test
  public void testInvalidatedMessage() throws Exception {
    InputStream is = marshaller.stream(proto);
    // Invalidates message, and drains all bytes
    byte[] unused = ByteStreams.toByteArray(is);
    try {
      ((ProtoInputStream) is).message();
      fail("Expected exception");
    } catch (IllegalStateException ex) {
      // expected
    }
    // Zero bytes is the default message
    assertEquals(Type.getDefaultInstance(), marshaller.parse(is));
  }

  @Test
  public void parseInvalid() {
    InputStream is = new ByteArrayInputStream(new byte[] {-127});
    try {
      marshaller.parse(is);
      fail("Expected exception");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.Code.INTERNAL, ex.getStatus().getCode());
      assertNotNull(((InvalidProtocolBufferException) ex.getCause()).getUnfinishedMessage());
    }
  }

  @Test
  public void testMismatch() {
    Marshaller<Enum> enumMarshaller = ProtoLiteUtils.marshaller(Enum.getDefaultInstance());
    // Enum's name and Type's name are both strings with tag 1.
    Enum altProto = Enum.newBuilder().setName(proto.getName()).build();
    assertEquals(proto, marshaller.parse(enumMarshaller.stream(altProto)));
  }

  @Test
  public void introspection() {
    Marshaller<Enum> enumMarshaller = ProtoLiteUtils.marshaller(Enum.getDefaultInstance());
    PrototypeMarshaller<Enum> prototypeMarshaller = (PrototypeMarshaller<Enum>) enumMarshaller;
    assertSame(Enum.getDefaultInstance(), prototypeMarshaller.getMessagePrototype());
    assertSame(Enum.class, prototypeMarshaller.getMessageClass());
  }

  @Test
  public void marshallerShouldNotLimitProtoSize() throws Exception {
    // The default limit is 64MB. Using a larger proto to verify that the limit is not enforced.
    byte[] bigName = new byte[70 * 1024 * 1024];
    Arrays.fill(bigName, (byte) 32);

    proto = Type.newBuilder().setNameBytes(ByteString.copyFrom(bigName)).build();

    // Just perform a round trip to verify that it works.
    testRoundtrip();
  }

  @Test
  public void testAvailable() throws Exception {
    InputStream is = marshaller.stream(proto);
    assertEquals(proto.getSerializedSize(), is.available());
    is.read();
    assertEquals(proto.getSerializedSize() - 1, is.available());
    while (is.read() != -1) {}
    assertEquals(-1, is.read());
    assertEquals(0, is.available());
  }

  @Test
  public void testEmpty() throws IOException {
    Marshaller<Empty> marshaller = ProtoLiteUtils.marshaller(Empty.getDefaultInstance());
    InputStream is = marshaller.stream(Empty.getDefaultInstance());
    assertEquals(0, is.available());
    byte[] b = new byte[10];
    assertEquals(-1, is.read(b));
    assertArrayEquals(new byte[10], b);
    // Do the same thing again, because the internal state may be different
    assertEquals(-1, is.read(b));
    assertArrayEquals(new byte[10], b);
    assertEquals(-1, is.read());
    assertEquals(0, is.available());
  }

  @Test
  public void testDrainTo_all() throws Exception {
    byte[] golden = ByteStreams.toByteArray(marshaller.stream(proto));
    InputStream is = marshaller.stream(proto);
    Drainable d = (Drainable) is;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int drained = d.drainTo(baos);
    assertEquals(baos.size(), drained);
    assertArrayEquals(golden, baos.toByteArray());
    assertEquals(0, is.available());
  }

  @Test
  public void testDrainTo_partial() throws Exception {
    final byte[] golden;
    {
      InputStream is = marshaller.stream(proto);
      is.read();
      golden = ByteStreams.toByteArray(is);
    }
    InputStream is = marshaller.stream(proto);
    is.read();
    Drainable d = (Drainable) is;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int drained = d.drainTo(baos);
    assertEquals(baos.size(), drained);
    assertArrayEquals(golden, baos.toByteArray());
    assertEquals(0, is.available());
  }

  @Test
  public void testDrainTo_none() throws Exception {
    InputStream is = marshaller.stream(proto);
    byte[] unused = ByteStreams.toByteArray(is);
    Drainable d = (Drainable) is;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    assertEquals(0, d.drainTo(baos));
    assertArrayEquals(new byte[0], baos.toByteArray());
    assertEquals(0, is.available());
  }

  @Test
  public void metadataMarshaller_roundtrip() {
    Metadata.BinaryMarshaller<Type> metadataMarshaller =
        ProtoLiteUtils.metadataMarshaller(Type.getDefaultInstance());
    assertEquals(proto, metadataMarshaller.parseBytes(metadataMarshaller.toBytes(proto)));
  }

  @Test
  public void metadataMarshaller_invalid() {
    Metadata.BinaryMarshaller<Type> metadataMarshaller =
        ProtoLiteUtils.metadataMarshaller(Type.getDefaultInstance());
    try {
      metadataMarshaller.parseBytes(new byte[] {-127});
      fail("Expected exception");
    } catch (IllegalArgumentException ex) {
      assertNotNull(((InvalidProtocolBufferException) ex.getCause()).getUnfinishedMessage());
    }
  }

  @Test
  public void extensionRegistry_notNull() {
    NullPointerException e = assertThrows(NullPointerException.class,
        () -> ProtoLiteUtils.setExtensionRegistry(null));
    assertThat(e).hasMessageThat().isEqualTo("newRegistry");
  }

  @Test
  public void parseFromKnowLengthInputStream() {
    Marshaller<Type> marshaller = ProtoLiteUtils.marshaller(Type.getDefaultInstance());
    Type expect = Type.newBuilder().setName("expected name").build();

    Type result = marshaller.parse(new CustomKnownLengthInputStream(expect.toByteArray()));
    assertEquals(expect, result);
  }

  @Test
  public void defaultMaxMessageSize() {
    assertEquals(GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE, ProtoLiteUtils.DEFAULT_MAX_MESSAGE_SIZE);
  }

  @Test
  public void testNullDefaultInstance() {
    String expectedMessage = "defaultInstance cannot be null";
    assertThrows(expectedMessage, NullPointerException.class,
        () -> ProtoLiteUtils.marshaller(null));

    assertThrows(expectedMessage, NullPointerException.class,
        () -> ProtoLiteUtils.marshallerWithRecursionLimit(null, 10)
    );
  }

  @Test
  public void givenPositiveLimit_testRecursionLimitExceeded() throws IOException {
    Marshaller<SimpleRecursiveMessage> marshaller = ProtoLiteUtils.marshallerWithRecursionLimit(
        SimpleRecursiveMessage.getDefaultInstance(), 10);
    SimpleRecursiveMessage message = buildRecursiveMessage(12);

    assertRecursionLimitExceeded(marshaller, message);
  }

  @Test
  public void givenZeroLimit_testRecursionLimitExceeded() throws IOException {
    Marshaller<SimpleRecursiveMessage> marshaller = ProtoLiteUtils.marshallerWithRecursionLimit(
        SimpleRecursiveMessage.getDefaultInstance(), 0);
    SimpleRecursiveMessage message = buildRecursiveMessage(1);

    assertRecursionLimitExceeded(marshaller, message);
  }

  @Test
  public void givenPositiveLimit_testRecursionLimitNotExceeded() throws IOException {
    Marshaller<SimpleRecursiveMessage> marshaller = ProtoLiteUtils.marshallerWithRecursionLimit(
        SimpleRecursiveMessage.getDefaultInstance(), 15);
    SimpleRecursiveMessage message = buildRecursiveMessage(12);

    assertRecursionLimitNotExceeded(marshaller, message);
  }

  @Test
  public void givenZeroLimit_testRecursionLimitNotExceeded() throws IOException {
    Marshaller<SimpleRecursiveMessage> marshaller = ProtoLiteUtils.marshallerWithRecursionLimit(
        SimpleRecursiveMessage.getDefaultInstance(), 0);
    SimpleRecursiveMessage message = buildRecursiveMessage(0);

    assertRecursionLimitNotExceeded(marshaller, message);
  }

  @Test
  public void testDefaultRecursionLimit() throws IOException {
    Marshaller<SimpleRecursiveMessage> marshaller = ProtoLiteUtils.marshaller(
        SimpleRecursiveMessage.getDefaultInstance());
    SimpleRecursiveMessage message = buildRecursiveMessage(100);

    assertRecursionLimitNotExceeded(marshaller, message);
  }

  private static void assertRecursionLimitExceeded(Marshaller<SimpleRecursiveMessage> marshaller,
      SimpleRecursiveMessage message) throws IOException {
    InputStream is = marshaller.stream(message);
    ByteArrayInputStream bais = new ByteArrayInputStream(ByteStreams.toByteArray(is));

    assertThrows(StatusRuntimeException.class, () -> marshaller.parse(bais));
  }

  private static void assertRecursionLimitNotExceeded(Marshaller<SimpleRecursiveMessage> marshaller,
      SimpleRecursiveMessage message) throws IOException {
    InputStream is = marshaller.stream(message);
    ByteArrayInputStream bais = new ByteArrayInputStream(ByteStreams.toByteArray(is));

    assertEquals(message, marshaller.parse(bais));
  }

  private static SimpleRecursiveMessage buildRecursiveMessage(int depth) {
    SimpleRecursiveMessage.Builder builder = SimpleRecursiveMessage.newBuilder()
        .setValue("depth-" + depth);
    for (int i = depth; i > 0; i--) {
      builder = SimpleRecursiveMessage.newBuilder()
          .setValue("depth-" + i)
          .setMessage(builder.build());
    }

    return builder.build();
  }

  private static class CustomKnownLengthInputStream extends InputStream implements KnownLength {

    private int position = 0;
    private final byte[] source;

    private CustomKnownLengthInputStream(byte[] source) {
      this.source = source;
    }

    @Override
    public int available() {
      return source.length - position;
    }

    @Override
    public int read() {
      if (position == source.length) {
        return -1;
      }

      return source[position++];
    }
  }
}
