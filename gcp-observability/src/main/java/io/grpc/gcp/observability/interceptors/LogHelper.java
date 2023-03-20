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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.InternalMetadata.BASE64_ENCODING_OMIT_PADDING;

import com.google.common.base.Joiner;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;
import io.grpc.Attributes;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.observabilitylog.v1.Address;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.Payload;
import io.opencensus.trace.SpanContext;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Helper class for GCP observability logging.
 */
@Internal
public class LogHelper {
  private static final Logger logger = Logger.getLogger(LogHelper.class.getName());

  // TODO(DNVindhya): Define it in one places(TBD) to make it easily accessible from everywhere
  static final Metadata.Key<byte[]> STATUS_DETAILS_KEY =
      Metadata.Key.of(
          "grpc-status-details-bin",
          Metadata.BINARY_BYTE_MARSHALLER);

  private final Sink sink;

  /**
   * Creates a LogHelper instance.
   *  @param sink sink
   *
   */
  public LogHelper(Sink sink) {
    this.sink = sink;
  }

  /**
   * Logs the request header. Binary logging equivalent of logClientHeader.
   */
  void logClientHeader(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      @Nullable Duration timeout,
      Metadata metadata,
      int maxHeaderBytes,
      GrpcLogRecord.EventLogger eventLogger,
      String callId,
      // null on client side
      @Nullable SocketAddress peerAddress,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(callId, "callId");
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.SERVER,
        "peerAddress can only be specified by server");
    PayloadBuilderHelper<Payload.Builder> pair =
        createMetadataProto(metadata, maxHeaderBytes);
    if (timeout != null) {
      pair.payloadBuilder.setTimeout(timeout);
    }
    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.CLIENT_HEADER)
        .setLogger(eventLogger)
        .setPayload(pair.payloadBuilder)
        .setPayloadTruncated(pair.truncated)
        .setCallId(callId);
    if (peerAddress != null) {
      logEntryBuilder.setPeer(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build(), spanContext);
  }

  /**
   * Logs the response header. Binary logging equivalent of logServerHeader.
   */
  void logServerHeader(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      Metadata metadata,
      int maxHeaderBytes,
      GrpcLogRecord.EventLogger eventLogger,
      String callId,
      @Nullable SocketAddress peerAddress,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(callId, "callId");
    // Logging peer address only on the first incoming event. On server side, peer address will
    // of logging request header
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.CLIENT,
        "peerAddress can only be specified for client");

    PayloadBuilderHelper<Payload.Builder> pair =
        createMetadataProto(metadata, maxHeaderBytes);
    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.SERVER_HEADER)
        .setLogger(eventLogger)
        .setPayload(pair.payloadBuilder)
        .setPayloadTruncated(pair.truncated)
        .setCallId(callId);
    if (peerAddress != null) {
      logEntryBuilder.setPeer(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build(), spanContext);
  }

  /**
   * Logs the server trailer.
   */
  void logTrailer(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      Status status,
      Metadata metadata,
      int maxHeaderBytes,
      GrpcLogRecord.EventLogger eventLogger,
      String callId,
      @Nullable SocketAddress peerAddress,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(status, "status");
    checkNotNull(callId, "callId");
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.CLIENT,
        "peerAddress can only be specified for client");

    PayloadBuilderHelper<Payload.Builder> pair =
        createMetadataProto(metadata, maxHeaderBytes);
    pair.payloadBuilder.setStatusCode(Code.forNumber(status.getCode().value()));
    String statusDescription = status.getDescription();
    if (statusDescription != null) {
      pair.payloadBuilder.setStatusMessage(statusDescription);
    }
    byte[] statusDetailBytes = metadata.get(STATUS_DETAILS_KEY);
    if (statusDetailBytes != null) {
      pair.payloadBuilder.setStatusDetails(ByteString.copyFrom(statusDetailBytes));
    }
    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.SERVER_TRAILER)
        .setLogger(eventLogger)
        .setPayload(pair.payloadBuilder)
        .setPayloadTruncated(pair.truncated)
        .setCallId(callId);
    if (peerAddress != null) {
      logEntryBuilder.setPeer(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build(), spanContext);
  }

  /**
   * Logs the RPC message.
   */
  <T> void logRpcMessage(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      EventType eventType,
      T message,
      int maxMessageBytes,
      EventLogger eventLogger,
      String callId,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(callId, "callId");
    checkArgument(
        eventType == EventType.CLIENT_MESSAGE
            || eventType == EventType.SERVER_MESSAGE,
        "event type must correspond to client message or server message");
    checkNotNull(message, "message");

    // TODO(DNVindhya): Implement conversion of generics to ByteString
    // Following is a temporary workaround to log if message is of following types :
    // 1. com.google.protobuf.Message
    // 2. byte[]
    byte[] messageBytesArray = null;
    if (message instanceof com.google.protobuf.Message) {
      messageBytesArray = ((com.google.protobuf.Message) message).toByteArray();
    } else if (message instanceof byte[]) {
      messageBytesArray = (byte[]) message;
    } else {
      logger.log(Level.WARNING, "message is of UNKNOWN type, message and payload_size fields "
          + "of GrpcLogRecord proto will not be logged");
    }
    PayloadBuilderHelper<Payload.Builder> pair = null;
    if (messageBytesArray != null) {
      pair = createMessageProto(messageBytesArray, maxMessageBytes);
    }
    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(eventType)
        .setLogger(eventLogger)
        .setCallId(callId);
    if (pair != null) {
      logEntryBuilder.setPayload(pair.payloadBuilder)
          .setPayloadTruncated(pair.truncated);
    }
    sink.write(logEntryBuilder.build(), spanContext);
  }

  /**
   * Logs half close.
   */
  void logHalfClose(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      GrpcLogRecord.EventLogger eventLogger,
      String callId,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(callId, "callId");

    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.CLIENT_HALF_CLOSE)
        .setLogger(eventLogger)
        .setCallId(callId);
    sink.write(logEntryBuilder.build(), spanContext);
  }

  /**
   * Logs cancellation.
   */
  void logCancel(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      GrpcLogRecord.EventLogger eventLogger,
      String callId,
      SpanContext spanContext) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(authority, "authority");
    checkNotNull(callId, "callId");

    GrpcLogRecord.Builder logEntryBuilder = GrpcLogRecord.newBuilder()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setType(EventType.CANCEL)
        .setLogger(eventLogger)
        .setCallId(callId);
    sink.write(logEntryBuilder.build(), spanContext);
  }

  // TODO(DNVindhya): Evaluate if we need following clause for metadata logging in GcpObservability
  // Leaving the implementation for now as is to have same behavior across Java and Go
  private static final Set<String> NEVER_INCLUDED_METADATA = new HashSet<>(
      Collections.singletonList(
          // grpc-status-details-bin is already logged in `status_details` field of the
          // observabilitylog proto
          STATUS_DETAILS_KEY.name()));
  private static final Set<String> ALWAYS_INCLUDED_METADATA = new HashSet<>(
      Collections.singletonList(
          "grpc-trace-bin"));

  static final class PayloadBuilderHelper<T> {
    T payloadBuilder;
    boolean truncated;

    private PayloadBuilderHelper(T payload, boolean truncated) {
      this.payloadBuilder = payload;
      this.truncated = truncated;
    }
  }

  static PayloadBuilderHelper<Payload.Builder> createMetadataProto(Metadata metadata,
      int maxHeaderBytes) {
    checkNotNull(metadata, "metadata");
    checkArgument(maxHeaderBytes >= 0,
        "maxHeaderBytes must be non negative");
    Joiner joiner = Joiner.on(",").skipNulls();
    Payload.Builder payloadBuilder = Payload.newBuilder();
    boolean truncated = false;
    int totalMetadataBytes = 0;
    for (String key : metadata.keys()) {
      if (NEVER_INCLUDED_METADATA.contains(key)) {
        continue;
      }
      boolean forceInclude = ALWAYS_INCLUDED_METADATA.contains(key);
      String metadataValue;
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Iterable<byte[]> metadataValues =
            metadata.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
        List<String> numList = new ArrayList<String>();
        metadataValues.forEach(
            (element) -> {
              numList.add(BASE64_ENCODING_OMIT_PADDING.encode(element));
            });
        metadataValue = joiner.join(numList);
      } else {
        Iterable<String> metadataValues = metadata.getAll(
            Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        metadataValue = joiner.join(metadataValues);
      }

      int metadataBytesAfterAdd = totalMetadataBytes + key.length() + metadataValue.length();
      if (!forceInclude && metadataBytesAfterAdd > maxHeaderBytes) {
        truncated = true;
        continue;
      }
      payloadBuilder.putMetadata(key, metadataValue);
      if (!forceInclude) {
        // force included keys do not count towards the size limit
        totalMetadataBytes = metadataBytesAfterAdd;
      }
    }
    return new PayloadBuilderHelper<>(payloadBuilder, truncated);
  }

  static PayloadBuilderHelper<Payload.Builder> createMessageProto(
      byte[] message, int maxMessageBytes) {
    checkArgument(maxMessageBytes >= 0,
        "maxMessageBytes must be non negative");
    Payload.Builder payloadBuilder = Payload.newBuilder();
    int desiredBytes = 0;
    int messageLength = message.length;
    if (maxMessageBytes > 0) {
      desiredBytes = Math.min(maxMessageBytes, messageLength);
    }
    ByteString messageData =
        ByteString.copyFrom(message, 0, desiredBytes);
    payloadBuilder.setMessage(messageData);
    payloadBuilder.setMessageLength(messageLength);

    return new PayloadBuilderHelper<>(payloadBuilder,
        maxMessageBytes < message.length);
  }

  static Address socketAddressToProto(SocketAddress address) {
    checkNotNull(address, "address");
    Address.Builder builder = Address.newBuilder();
    if (address instanceof InetSocketAddress) {
      InetAddress inetAddress = ((InetSocketAddress) address).getAddress();
      if (inetAddress instanceof Inet4Address) {
        builder.setType(Address.Type.IPV4)
            .setAddress(InetAddressUtil.toAddrString(inetAddress));
      } else if (inetAddress instanceof Inet6Address) {
        builder.setType(Address.Type.IPV6)
            .setAddress(InetAddressUtil.toAddrString(inetAddress));
      } else {
        logger.log(Level.SEVERE, "unknown type of InetSocketAddress: {}", address);
        builder.setAddress(address.toString());
      }
      builder.setIpPort(((InetSocketAddress) address).getPort());
    } else if (address.getClass().getName().equals("io.netty.channel.unix.DomainSocketAddress")) {
      // To avoid a compiled time dependency on grpc-netty, we check against the
      // runtime class name.
      builder.setType(Address.Type.UNIX)
          .setAddress(address.toString());
    } else {
      builder.setType(Address.Type.TYPE_UNKNOWN).setAddress(address.toString());
    }
    return builder.build();
  }

  /**
   * Retrieves socket address.
   */
  static SocketAddress getPeerAddress(Attributes streamAttributes) {
    return streamAttributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
  }

  /**
   * Checks deadline for timeout.
   */
  static Deadline min(@Nullable Deadline deadline0, @Nullable Deadline deadline1) {
    if (deadline0 == null) {
      return deadline1;
    }
    if (deadline1 == null) {
      return deadline0;
    }
    return deadline0.minimum(deadline1);
  }
}
