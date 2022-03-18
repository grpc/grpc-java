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

package io.grpc.observability.interceptors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Charsets;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Timestamps;
import io.grpc.Attributes;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.Address;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.GrpcLogRecord.LogLevel;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Helper class for GCP observability logging.
 */
class LogHelper {
  private static final Logger logger = Logger.getLogger(LogHelper.class.getName());

  // TODO(dnvindhya): Define it in one places(TBD) to make it easily accessible from everywhere
  static final Metadata.Key<byte[]> STATUS_DETAILS_KEY =
      Metadata.Key.of(
          "grpc-status-details-bin",
          Metadata.BINARY_BYTE_MARSHALLER);

  private final Sink sink;
  private final TimeProvider timeProvider;

  LogHelper(Sink sink, TimeProvider timeProvider) {
    this.sink = sink;
    this.timeProvider = timeProvider;
  }

  /**
   * Logs the request header. Binary logging equivalent of logClientHeader.
   */
  void logRequestHeader(
      long seqId,
      String serviceName,
      String methodName,
      String authority,
      @Nullable Duration timeout,
      Metadata metadata,
      GrpcLogRecord.EventLogger eventLogger,
      String rpcId,
      // null on client side
      @Nullable SocketAddress peerAddress) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(rpcId, "rpcId");
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_SERVER,
        "peerAddress can only be specified by server");

    PayloadBuilder<GrpcLogRecord.Metadata.Builder> pair = createMetadataProto(metadata);
    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setAuthority(authority)
        .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setMetadata(pair.payload)
        .setPayloadSize(pair.size)
        .setRpcId(rpcId);
    if (timeout != null) {
      logEntryBuilder.setTimeout(timeout);
    }
    if (peerAddress != null) {
      logEntryBuilder.setPeerAddress(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build());
  }

  /**
   * Logs the reponse header. Binary logging equivalent of logServerHeader.
   */
  void logResponseHeader(
      long seqId,
      String serviceName,
      String methodName,
      Metadata metadata,
      GrpcLogRecord.EventLogger eventLogger,
      String rpcId,
      @Nullable SocketAddress peerAddress) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(rpcId, "rpcId");
    // Logging peer address only on the first incoming event. On server side, peer address will
    // of logging request header
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_CLIENT,
        "peerAddress can only be specified for client");

    PayloadBuilder<GrpcLogRecord.Metadata.Builder> pair = createMetadataProto(metadata);
    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(EventType.GRPC_CALL_RESPONSE_HEADER)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setMetadata(pair.payload)
        .setPayloadSize(pair.size)
        .setRpcId(rpcId);
    if (peerAddress != null) {
      logEntryBuilder.setPeerAddress(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build());
  }

  /**
   * Logs the server trailer.
   */
  void logTrailer(
      long seqId,
      String serviceName,
      String methodName,
      Status status,
      Metadata metadata,
      GrpcLogRecord.EventLogger eventLogger,
      String rpcId,
      @Nullable SocketAddress peerAddress) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(status, "status");
    checkNotNull(rpcId, "rpcId");
    checkArgument(
        peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_CLIENT,
        "peerAddress can only be specified for client");

    PayloadBuilder<GrpcLogRecord.Metadata.Builder> pair = createMetadataProto(metadata);
    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(EventType.GRPC_CALL_TRAILER)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setMetadata(pair.payload)
        .setPayloadSize(pair.size)
        .setStatusCode(status.getCode().value())
        .setRpcId(rpcId);
    String statusDescription = status.getDescription();
    if (statusDescription != null) {
      logEntryBuilder.setStatusMessage(statusDescription);
    }
    byte[] statusDetailBytes = metadata.get(STATUS_DETAILS_KEY);
    if (statusDetailBytes != null) {
      logEntryBuilder.setStatusDetails(ByteString.copyFrom(statusDetailBytes));
    }
    if (peerAddress != null) {
      logEntryBuilder.setPeerAddress(socketAddressToProto(peerAddress));
    }
    sink.write(logEntryBuilder.build());
  }

  /**
   * Logs the RPC message.
   */
  <T> void logRpcMessage(
      long seqId,
      String serviceName,
      String methodName,
      EventType eventType,
      T message,
      EventLogger eventLogger,
      String rpcId) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(rpcId, "rpcId");
    checkArgument(
        eventType == EventType.GRPC_CALL_REQUEST_MESSAGE
            || eventType == EventType.GRPC_CALL_RESPONSE_MESSAGE,
        "event type must correspond to client message or server message");
    checkNotNull(message, "message");

    // TODO(dnvindhya): Implement conversion of generics to ByteString
    // Following is a temporary workaround to log if message is of following types :
    // 1. com.google.protobuf.Message
    // 2. byte[]
    byte[] messageBytesArray = null;
    if (message instanceof com.google.protobuf.Message) {
      messageBytesArray = ((com.google.protobuf.Message)message).toByteArray();
    } else if (message instanceof byte[]) {
      messageBytesArray = (byte[]) message;
    } else {
      logger.log(Level.WARNING, "message is of UNKNOWN type, message and payload_size fields"
          + "of GrpcLogRecord proto will not be logged");
    }
    PayloadBuilder<ByteString> pair = null;
    if (messageBytesArray != null) {
      pair = createMesageProto(messageBytesArray);
    }

    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(eventType)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setRpcId(rpcId);
    if (pair != null && pair.size != 0) {
      logEntryBuilder.setPayloadSize(pair.size);
    }
    if (pair != null && pair.payload != null) {
      logEntryBuilder.setMessage(pair.payload);
    }
    sink.write(logEntryBuilder.build());
  }

  /**
   * Logs half close.
   */
  void logHalfClose(
      long seqId,
      String serviceName,
      String methodName,
      GrpcLogRecord.EventLogger eventLogger,
      String rpcId) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(rpcId, "rpcId");

    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(EventType.GRPC_CALL_HALF_CLOSE)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setRpcId(rpcId);
    sink.write(logEntryBuilder.build());
  }

  /**
   * Logs cancellation.
   */
  void logCancel(
      long seqId,
      String serviceName,
      String methodName,
      GrpcLogRecord.EventLogger eventLogger,
      String rpcId) {
    checkNotNull(serviceName, "serviceName");
    checkNotNull(methodName, "methodName");
    checkNotNull(rpcId, "rpcId");

    GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
        .setSequenceId(seqId)
        .setServiceName(serviceName)
        .setMethodName(methodName)
        .setEventType(EventType.GRPC_CALL_CANCEL)
        .setEventLogger(eventLogger)
        .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
        .setRpcId(rpcId);
    sink.write(logEntryBuilder.build());
  }

  GrpcLogRecord.Builder createTimestamp() {
    long nanos = timeProvider.currentTimeNanos();
    return GrpcLogRecord.newBuilder().setTimestamp(Timestamps.fromNanos(nanos));
  }

  static final class PayloadBuilder<T> {
    T payload;
    int size;

    private PayloadBuilder(T payload, int size) {
      this.payload = payload;
      this.size = size;
    }
  }

  // TODO(dnvindhya): Create a unit test for the metadata conversion
  static PayloadBuilder<GrpcLogRecord.Metadata.Builder> createMetadataProto(Metadata metadata) {
    checkNotNull(metadata, "metadata");
    GrpcLogRecord.Metadata.Builder metadataBuilder = GrpcLogRecord.Metadata.newBuilder();
    // This code is tightly coupled with io.grpc.observabilitylog.v1.GrpcLogRecord.Metadata's
    // implementation
    byte[][] serialized = InternalMetadata.serialize(metadata);
    int totalMetadataBytes = 0;
    if (serialized != null) {
      int singleMetadataEntryBytes = 0;
      // Calculate bytes for each GrpcLogRecord.Metadata.MetadataEntry
      for (int i = 0; i < serialized.length; i += 2) {
        String key = new String(serialized[i], Charsets.UTF_8);
        byte[] value = serialized[i + 1];
        singleMetadataEntryBytes = totalMetadataBytes + key.length() + value.length;
        metadataBuilder.addEntryBuilder()
            .setKey(key)
            .setValue(ByteString.copyFrom(value));
        totalMetadataBytes = singleMetadataEntryBytes;
      }
    }
    return new PayloadBuilder<>(metadataBuilder, totalMetadataBytes);
  }

  static PayloadBuilder<ByteString> createMesageProto(byte[] message) {
    int messageLength = message.length;
    ByteString messageData =
        ByteString.copyFrom(message, 0, messageLength);
    return new PayloadBuilder<ByteString>(messageData, messageLength);
  }

  static Address socketAddressToProto(SocketAddress address) {
    checkNotNull(address, "address");
    Address.Builder builder = Address.newBuilder();
    if (address instanceof InetSocketAddress) {
      InetAddress inetAddress = ((InetSocketAddress) address).getAddress();
      if (inetAddress instanceof Inet4Address) {
        builder.setType(Address.Type.TYPE_IPV4)
            .setAddress(InetAddressUtil.toAddrString(inetAddress));
      } else if (inetAddress instanceof Inet6Address) {
        builder.setType(Address.Type.TYPE_IPV6)
            .setAddress(InetAddressUtil.toAddrString(inetAddress));
      } else {
        logger.log(Level.SEVERE, "unknown type of InetSocketAddress: {}", address);
        builder.setAddress(address.toString());
      }
      builder.setIpPort(((InetSocketAddress) address).getPort());
    } else if (address.getClass().getName().equals("io.netty.channel.unix.DomainSocketAddress")) {
      // To avoid a compile time dependency on grpc-netty, we check against the
      // runtime class name.
      builder.setType(Address.Type.TYPE_UNIX)
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

  // TODO (dnvindhya) : Implement service and method name filtering
  // Add unit tests for the method as part of filtering implementation
  boolean isMethodToBeLogged(String fullMethodName) {
    return true;
  }
}
