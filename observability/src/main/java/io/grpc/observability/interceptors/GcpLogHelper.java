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
final class GcpLogHelper {
  private static final Logger logger = Logger.getLogger(GcpLogHelper.class.getName());
  static final Metadata.Key<byte[]> STATUS_DETAILS_KEY =
      Metadata.Key.of(
          "grpc-status-details-bin",
          Metadata.BINARY_BYTE_MARSHALLER);

  /**
   *  Class for proto related helper methods.
   */
  static final class GcpLogSinkWriter {
    private final Sink sink;
    private final TimeProvider timeProvider;

    GcpLogSinkWriter(Sink sink, TimeProvider timeProvider) {
      this.sink = sink;
      this.timeProvider = timeProvider;
    }

    GrpcLogRecord.Builder createTimestamp() {
      long nanos = timeProvider.currentTimeNanos();
      return GrpcLogRecord.newBuilder().setTimestamp(Timestamps.fromNanos(nanos));
    }


    /**
     * Logs the client header.
     */
    void logClientHeader(
        long seqId,
        String serviceName,
        String methodName,
        @Nullable String authority,
        @Nullable Duration timeout,
        Metadata metadata,
        GrpcLogRecord.EventLogger eventLogger,
        String rpcId,
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
          .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
          .setMetadata(pair.proto)
          .setPayloadSize(pair.size)
          .setRpcId(rpcId);
      if (authority != null) {
        logEntryBuilder.setAuthority(authority);
      }
      if (timeout != null) {
        logEntryBuilder.setTimeout(timeout);
      }
      if (peerAddress != null) {
        logEntryBuilder.setPeerAddress(socketToProto(peerAddress));
      }
      logger.log(Level.FINE, "Writing Request Header to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

    /**
     * Logs the server header.
     */
    void logServerHeader(
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
          .setMetadata(pair.proto)
          .setPayloadSize(pair.size)
          .setRpcId(rpcId);
      if (peerAddress != null) {
        logEntryBuilder.setPeerAddress(socketToProto(peerAddress));
      }
      logger.log(Level.FINE, "Writing Response Header to GcpLogSink");
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
          .setMetadata(pair.proto)
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
        logEntryBuilder.setPeerAddress(socketToProto(peerAddress));
      }
      logger.log(Level.FINE, "Writing Server Trailer to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

    /**
     * Logs the RPC message.
     */
    <T> void logRpcMessage(
        long seqId,
        String serviceName,
        String methodName,
        GrpcLogRecord.EventType eventType,
        T message,
        GrpcLogRecord.EventLogger eventLogger,
        String rpcId) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");
      checkNotNull(rpcId, "rpcId");
      checkArgument(
          eventType == EventType.GRPC_CALL_REQUEST_MESSAGE
              || eventType == EventType.GRPC_CALL_RESPONSE_MESSAGE,
          "event type must correspond to client message or server message");
      checkNotNull(message, "message");

      // TODO(dnvindhya): Convert message to bystestring
      // byte[] messageArray = (byte[])message;
      // int messageLength = messageArray.length;
      // ByteString messageData =
      // ByteString.copyFrom((byte[]) message, 0, ((byte[]) message).length);

      GrpcLogRecord.Builder logEntryBuilder = createTimestamp()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(eventType)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
          .setRpcId(rpcId);
      logger.log(Level.FINE, "Writing RPC message to GcpLogSink");
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
      logger.log(Level.FINE, "Writing Half Close event to GcpLogSink");
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
      logger.log(Level.FINE, "Writing Cancel event to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }
  }

  static final class PayloadBuilder<T> {
    T proto;
    int size;

    private PayloadBuilder(T proto, int size) {
      this.proto = proto;
      this.size = size;
    }
  }

  static PayloadBuilder<GrpcLogRecord.Metadata.Builder> createMetadataProto(Metadata metadata) {
    checkNotNull(metadata, "metadata");
    GrpcLogRecord.Metadata.Builder metadataBuilder = GrpcLogRecord.Metadata.newBuilder();
    byte[][] serialized = InternalMetadata.serialize(metadata);
    int bytesAfterAdd = 0;
    if (serialized != null) {
      int curBytes = 0;
      for (int i = 0; i < serialized.length; i += 2) {
        String key = new String(serialized[i], Charsets.UTF_8);
        byte[] value = serialized[i + 1];
        bytesAfterAdd = curBytes + key.length() + value.length;
        metadataBuilder.addEntryBuilder()
            .setKey(key)
            .setValue(ByteString.copyFrom(value));
      }
    }
    return new PayloadBuilder<>(metadataBuilder, bytesAfterAdd);
  }

  static Address socketToProto(SocketAddress address) {
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
   *  Retrieves socket address.
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
