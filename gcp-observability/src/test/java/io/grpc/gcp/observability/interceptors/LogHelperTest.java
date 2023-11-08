/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import com.google.rpc.Code;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.Status;
import io.grpc.gcp.observability.interceptors.LogHelper.PayloadBuilderHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.observabilitylog.v1.Address;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.Payload;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.Tracestate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link LogHelper}.
 */
@RunWith(JUnit4.class)
public class LogHelperTest {
  public static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();
  private static final String DATA_A = "aaaaaaaaa";
  private static final String DATA_B = "bbbbbbbbb";
  private static final String DATA_C = "ccccccccc";
  private static final Metadata.Key<String> KEY_A =
      Metadata.Key.of("a", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> KEY_B =
      Metadata.Key.of("b", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> KEY_C =
      Metadata.Key.of("c", Metadata.ASCII_STRING_MARSHALLER);
  private static final int HEADER_LIMIT = 10;
  private static final int MESSAGE_LIMIT = Integer.MAX_VALUE;
  private static final SpanContext CLIENT_SPAN_CONTEXT = SpanContext.create(
      TraceId.fromLowerBase16("4c6af40c499951eb7de2777ba1e4fefa"),
      SpanId.fromLowerBase16("de52e84d13dd232d"),
      TraceOptions.builder().setIsSampled(true).build(),
      Tracestate.builder().build());
  private static final SpanContext SERVER_SPAN_CONTEXT = SpanContext.create(
      TraceId.fromLowerBase16("549a8a64db2d0c757fdf6bb1bfe84e2c"),
      SpanId.fromLowerBase16("a5b7704614fe903d"),
      TraceOptions.builder().setIsSampled(true).build(),
      Tracestate.builder().build());

  private final Metadata nonEmptyMetadata = new Metadata();
  private final Sink sink = mock(GcpLogSink.class);
  private final LogHelper logHelper = new LogHelper(sink);


  @Before
  public void setUp() {
    nonEmptyMetadata.put(KEY_A, DATA_A);
    nonEmptyMetadata.put(KEY_B, DATA_B);
    nonEmptyMetadata.put(KEY_C, DATA_C);
  }

  @Test
  public void socketToProto_ipv4() throws Exception {
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress socketAddress = new InetSocketAddress(address, port);
    assertThat(LogHelper.socketAddressToProto(socketAddress))
        .isEqualTo(Address
            .newBuilder()
            .setType(Address.Type.IPV4)
            .setAddress("127.0.0.1")
            .setIpPort(12345)
            .build());
  }

  @Test
  public void socketToProto_ipv6() throws Exception {
    // this is a ipv6 link local address
    InetAddress address = InetAddress.getByName("2001:db8:0:0:0:0:2:1");
    int port = 12345;
    InetSocketAddress socketAddress = new InetSocketAddress(address, port);
    assertThat(LogHelper.socketAddressToProto(socketAddress))
        .isEqualTo(Address
            .newBuilder()
            .setType(Address.Type.IPV6)
            .setAddress("2001:db8::2:1") // RFC 5952 section 4: ipv6 canonical form required
            .setIpPort(12345)
            .build());
  }

  @Test
  public void socketToProto_unknown() {
    SocketAddress unknownSocket = new SocketAddress() {
      @Override
      public String toString() {
        return "some-socket-address";
      }
    };
    assertThat(LogHelper.socketAddressToProto(unknownSocket))
        .isEqualTo(Address.newBuilder()
            .setType(Address.Type.TYPE_UNKNOWN)
            .setAddress("some-socket-address")
            .build());
  }

  @Test
  public void metadataToProto_empty() {
    assertThat(metadataToProtoTestHelper(
            EventType.CLIENT_HEADER, new Metadata(), Integer.MAX_VALUE))
        .isEqualTo(GrpcLogRecord.newBuilder()
            .setType(EventType.CLIENT_HEADER)
            .setPayload(
                Payload.newBuilder().putAllMetadata(new HashMap<>()))
            .build());
  }

  @Test
  public void metadataToProto() {
    Payload.Builder payloadBuilder = Payload.newBuilder()
        .putMetadata("a", DATA_A)
            .putMetadata("b", DATA_B)
                .putMetadata("c", DATA_C);

    assertThat(metadataToProtoTestHelper(
            EventType.CLIENT_HEADER, nonEmptyMetadata, Integer.MAX_VALUE))
        .isEqualTo(GrpcLogRecord.newBuilder()
            .setType(EventType.CLIENT_HEADER)
            .setPayload(payloadBuilder)
            .build());
  }

  @Test
  public void metadataToProto_setsTruncated() {
    assertTrue(LogHelper.createMetadataProto(nonEmptyMetadata, 0).truncated);
  }

  @Test
  public void metadataToProto_truncated() {
    // 0 byte limit not enough for any metadata
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 0).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder()
                .putAllMetadata(new HashMap<>())
                .build());
    // not enough bytes for first key value
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 9).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder()
                .putAllMetadata(new HashMap<>())
                .build());
    // enough for first key value
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 10).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder().putMetadata("a", DATA_A).build());
    // Test edge cases for >= 2 key values
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 19).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder().putMetadata("a", DATA_A).build());
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 20).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder()
                .putMetadata("a", DATA_A)
                .putMetadata("b", DATA_B)
                .build());
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 29).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder()
                .putMetadata("a", DATA_A)
                .putMetadata("b", DATA_B)
                .build());
    // not truncated: enough for all keys
    assertThat(LogHelper.createMetadataProto(nonEmptyMetadata, 30).payloadBuilder.build())
        .isEqualTo(
            io.grpc.observabilitylog.v1.Payload.newBuilder()
                .putMetadata("a", DATA_A)
                .putMetadata("b", DATA_B)
                .putMetadata("c", DATA_C)
                .build());
  }

  @Test
  public void messageToProto() {
    byte[] bytes
        = "this is a long message: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes(
        StandardCharsets.US_ASCII);
    assertThat(messageTestHelper(bytes, Integer.MAX_VALUE))
        .isEqualTo(GrpcLogRecord.newBuilder()
            .setPayload(
                Payload.newBuilder()
                    .setMessage(
                        ByteString.copyFrom(bytes))
                    .setMessageLength(bytes.length))
            .build());
  }

  @Test
  public void messageToProto_truncated() {
    byte[] bytes
        = "this is a long message: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes(
        StandardCharsets.US_ASCII);
    assertThat(messageTestHelper(bytes, 0))
        .isEqualTo(GrpcLogRecord.newBuilder()
            .setPayload(
                Payload.newBuilder()
                    .setMessageLength(bytes.length))
            .setPayloadTruncated(true)
            .build());

    int limit = 10;
    String truncatedMessage = "this is a ";
    assertThat(messageTestHelper(bytes, limit))
        .isEqualTo(
            GrpcLogRecord.newBuilder()
                .setPayload(
                    Payload.newBuilder()
                        .setMessage(
                            ByteString.copyFrom(
                                truncatedMessage.getBytes(StandardCharsets.US_ASCII)))
                        .setMessageLength(bytes.length))
                .setPayloadTruncated(true)
                .build());
  }


  @Test
  public void logRequestHeader() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String authority = "authority";
    Duration timeout = Durations.fromMillis(1234);
    String callId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peerAddress = new InetSocketAddress(address, port);

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.CLIENT_HEADER, nonEmptyMetadata,
            HEADER_LIMIT)
            .toBuilder()
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setType(EventType.CLIENT_HEADER)
            .setLogger(EventLogger.CLIENT)
            .setCallId(callId);
    builder.setAuthority(authority);
    builder.setPayload(builder.getPayload().toBuilder().setTimeout(timeout).build());
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logClientHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          null,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(base, CLIENT_SPAN_CONTEXT);
    }

    // logged on server
    {
      logHelper.logClientHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.SERVER,
          callId,
          peerAddress,
          SERVER_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setPeer(LogHelper.socketAddressToProto(peerAddress))
              .setLogger(EventLogger.SERVER)
              .build(),
          SERVER_SPAN_CONTEXT);
    }

    // timeout is null
    {
      logHelper.logClientHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          null,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          null,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setPayload(base.getPayload().toBuilder().clearTimeout().build())
              .build(),
          CLIENT_SPAN_CONTEXT);
    }

    // peerAddress is not null (error on client)
    try {
      logHelper.logClientHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          timeout,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          peerAddress,
          CLIENT_SPAN_CONTEXT);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("peerAddress can only be specified by server");
    }
  }

  @Test
  public void logResponseHeader() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String authority = "authority";
    String callId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peerAddress = new InetSocketAddress(address, port);

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.SERVER_HEADER, nonEmptyMetadata,
            HEADER_LIMIT)
            .toBuilder()
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setAuthority(authority)
            .setType(EventType.SERVER_HEADER)
            .setLogger(EventLogger.CLIENT)
            .setCallId(callId);
    builder.setPeer(LogHelper.socketAddressToProto(peerAddress));
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logServerHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          peerAddress,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(base, CLIENT_SPAN_CONTEXT);
    }

    // logged on server
    {
      logHelper.logServerHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.SERVER,
          callId,
          null,
          SERVER_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setLogger(EventLogger.SERVER)
              .clearPeer()
              .build(),
          SERVER_SPAN_CONTEXT);
    }

    // peerAddress is not null (error on server)
    try {
      logHelper.logServerHeader(
          seqId,
          serviceName,
          methodName,
          authority,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.SERVER,
          callId,
          peerAddress,
          SERVER_SPAN_CONTEXT);

      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat()
          .contains("peerAddress can only be specified for client");
    }
  }

  @Test
  public void logTrailer() throws Exception {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String authority = "authority";
    String callId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    InetAddress address = InetAddress.getByName("127.0.0.1");
    int port = 12345;
    InetSocketAddress peer = new InetSocketAddress(address, port);
    Status statusDescription = Status.INTERNAL.withDescription("test description");

    GrpcLogRecord.Builder builder =
        metadataToProtoTestHelper(EventType.SERVER_HEADER, nonEmptyMetadata,
            HEADER_LIMIT)
            .toBuilder()
            .setSequenceId(seqId)
            .setServiceName(serviceName)
            .setMethodName(methodName)
            .setAuthority(authority)
            .setType(EventType.SERVER_TRAILER)
            .setLogger(EventLogger.CLIENT)
            .setCallId(callId);
    builder.setPeer(LogHelper.socketAddressToProto(peer));
    builder.setPayload(
        builder.getPayload().toBuilder()
            .setStatusCode(Code.forNumber(Status.INTERNAL.getCode().value()))
            .setStatusMessage("test description")
            .build());
    GrpcLogRecord base = builder.build();

    // logged on client
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          authority,
          statusDescription,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          peer,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(base, CLIENT_SPAN_CONTEXT);
    }

    // logged on server
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          authority,
          statusDescription,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.SERVER,
          callId,
          null,
          SERVER_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .clearPeer()
              .setLogger(EventLogger.SERVER)
              .build(),
          SERVER_SPAN_CONTEXT);
    }

    // peer address is null
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          authority,
          statusDescription,
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          null,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .clearPeer()
              .build(),
          CLIENT_SPAN_CONTEXT);
    }

    // status description is null
    {
      logHelper.logTrailer(
          seqId,
          serviceName,
          methodName,
          authority,
          statusDescription.getCode().toStatus(),
          nonEmptyMetadata,
          HEADER_LIMIT,
          EventLogger.CLIENT,
          callId,
          peer,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setPayload(base.getPayload().toBuilder().clearStatusMessage().build())
              .build(),
          CLIENT_SPAN_CONTEXT);
    }
  }

  @Test
  public void alwaysLoggedMetadata_grpcTraceBin() {
    Metadata.Key<byte[]> key
        = Metadata.Key.of("grpc-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    Metadata metadata = new Metadata();
    metadata.put(key, new byte[1]);
    int zeroHeaderBytes = 0;
    PayloadBuilderHelper<Payload.Builder> pair =
        LogHelper.createMetadataProto(metadata, zeroHeaderBytes);
    assertThat(pair.payloadBuilder.build().getMetadataMap().containsKey(key.name())).isTrue();
    assertFalse(pair.truncated);
  }

  @Test
  public void neverLoggedMetadata_grpcStatusDetailsBin() {
    Metadata.Key<byte[]> key
        = Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);
    Metadata metadata = new Metadata();
    metadata.put(key, new byte[1]);
    int unlimitedHeaderBytes = Integer.MAX_VALUE;
    PayloadBuilderHelper<Payload.Builder> pair
        = LogHelper.createMetadataProto(metadata, unlimitedHeaderBytes);
    assertThat(pair.payloadBuilder.getMetadataMap()).isEmpty();
    assertFalse(pair.truncated);
  }

  @Test
  public void logRpcMessage() {
    long seqId = 1;
    String serviceName = "service";
    String methodName = "method";
    String authority = "authority";
    String callId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
    byte[] message = new byte[100];

    GrpcLogRecord.Builder builder = messageTestHelper(message, MESSAGE_LIMIT)
        .toBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.CLIENT_MESSAGE)
        .setLogger(EventLogger.CLIENT)
        .setCallId(callId);
    GrpcLogRecord base = builder.build();
    // request message
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          authority,
          EventType.CLIENT_MESSAGE,
          message,
          MESSAGE_LIMIT,
          EventLogger.CLIENT,
          callId,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(base, CLIENT_SPAN_CONTEXT);
    }
    // response message, logged on client
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          authority,
          EventType.SERVER_MESSAGE,
          message,
          MESSAGE_LIMIT,
          EventLogger.CLIENT,
          callId,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setType(EventType.SERVER_MESSAGE)
              .build(),
          CLIENT_SPAN_CONTEXT);
    }
    // request message, logged on server
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          authority,
          EventType.CLIENT_MESSAGE,
          message,
          MESSAGE_LIMIT,
          EventLogger.SERVER,
          callId,
          SERVER_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .setLogger(EventLogger.SERVER)
              .build(),
          SERVER_SPAN_CONTEXT);
    }
    // response message, logged on server
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          authority,
          EventType.SERVER_MESSAGE,
          message,
          MESSAGE_LIMIT,
          EventLogger.SERVER,
          callId,
          SpanContext.INVALID);
      verify(sink).write(
          base.toBuilder()
              .setType(EventType.SERVER_MESSAGE)
              .setLogger(EventLogger.SERVER)
              .build(),
          SpanContext.INVALID);
    }
    // message is not of type : com.google.protobuf.Message or byte[]
    {
      logHelper.logRpcMessage(
          seqId,
          serviceName,
          methodName,
          authority,
          EventType.CLIENT_MESSAGE,
          "message",
          MESSAGE_LIMIT,
          EventLogger.CLIENT,
          callId,
          CLIENT_SPAN_CONTEXT);
      verify(sink).write(
          base.toBuilder()
              .clearPayload()
              .clearPayloadTruncated()
              .build(),
          CLIENT_SPAN_CONTEXT);
    }
  }

  @Test
  public void getPeerAddressTest() throws Exception {
    SocketAddress peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
    assertNull(LogHelper.getPeerAddress(Attributes.EMPTY));
    assertSame(
        peer,
        LogHelper.getPeerAddress(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build()));
  }

  private static GrpcLogRecord metadataToProtoTestHelper(
      EventType type, Metadata metadata, int maxHeaderBytes) {
    GrpcLogRecord.Builder builder = GrpcLogRecord.newBuilder();
    PayloadBuilderHelper<Payload.Builder> pair
        = LogHelper.createMetadataProto(metadata, maxHeaderBytes);
    builder.setPayload(pair.payloadBuilder);
    builder.setPayloadTruncated(pair.truncated);
    builder.setType(type);
    return builder.build();
  }

  private static GrpcLogRecord messageTestHelper(byte[] message, int maxMessageBytes) {
    GrpcLogRecord.Builder builder = GrpcLogRecord.newBuilder();
    PayloadBuilderHelper<Payload.Builder> pair
        = LogHelper.createMessageProto(message, maxMessageBytes);
    builder.setPayload(pair.payloadBuilder);
    builder.setPayloadTruncated(pair.truncated);
    return builder.build();
  }

  // Used only in tests
  // Copied from internal
  static final class ByteArrayMarshaller implements Marshaller<byte[]> {

    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return parseHelper(stream);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private byte[] parseHelper(InputStream stream) throws IOException {
      try {
        return IoUtils.toByteArray(stream);
      } finally {
        stream.close();
      }
    }
  }

  // Copied from internal
  static final class IoUtils {

    /** maximum buffer to be read is 16 KB. */
    private static final int MAX_BUFFER_LENGTH = 16384;

    /** Returns the byte array. */
    public static byte[] toByteArray(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      copy(in, out);
      return out.toByteArray();
    }

    /** Copies the data from input stream to output stream. */
    public static long copy(InputStream from, OutputStream to) throws IOException {
      // Copied from guava com.google.common.io.ByteStreams because its API is unstable (beta)
      checkNotNull(from);
      checkNotNull(to);
      byte[] buf = new byte[MAX_BUFFER_LENGTH];
      long total = 0;
      while (true) {
        int r = from.read(buf);
        if (r == -1) {
          break;
        }
        to.write(buf, 0, r);
        total += r;
      }
      return total;
    }
  }
}
