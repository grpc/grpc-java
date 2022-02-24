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

package io.grpc.observability.logging;

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
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.Address;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.GrpcLogRecord.LogLevel;
import java.net.SocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Helper class for GCP observability logging.
 */
public final class GcpLogHelper {
  private static final Logger logger = Logger.getLogger(GcpLogHelper.class.getName());
  static final Metadata.Key<byte[]> STATUS_DETAILS_KEY =
      Metadata.Key.of(
          "grpc-status-details-bin",
          Metadata.BINARY_BYTE_MARSHALLER);

  /**
   *  Class for proto related helper methods.
   */
  public static final class GcpLogSinkWriter {
    private final GcpLogSink sink;
    private TimeProvider timeProvider;

    public GcpLogSinkWriter(GcpLogSink sink, TimeProvider timeProvider) {
      this.sink = sink;
      this.timeProvider = timeProvider;
    }

    GrpcLogRecord.Builder newTimestampBuilder() {
      long nanos = timeProvider.currentTimeNanos();
      return GrpcLogRecord.newBuilder().setTimestamp(Timestamps.fromNanos(nanos));
    }

    // TODO(dnvindhya) : Return header size as well
    GrpcLogRecord.Metadata.Builder createMetadataProto(Metadata metadata) {
      checkNotNull(metadata, "metadata");
      GrpcLogRecord.Metadata.Builder metadataBuilder = GrpcLogRecord.Metadata.newBuilder();
      byte[][] serialized = InternalMetadata.serialize(metadata);
      if (serialized != null) {
        // int curBytes = 0;
        for (int i = 0; i < serialized.length; i += 2) {
          String key = new String(serialized[i], Charsets.UTF_8);
          byte[] value = serialized[i + 1];
          // int bytesAfterAdd = curBytes + key.length() + value.length;
          metadataBuilder.addEntryBuilder()
              .setKey(key)
              .setValue(ByteString.copyFrom(value));
        }
      }
      return metadataBuilder;
    }

    static Address socketToProto(SocketAddress address) {
      checkNotNull(address, "address");
      Address.Builder builder = Address.newBuilder();
      // TODO(dnvindhya): Check for type of InetSockAddress
      builder.setType(Address.Type.TYPE_UNKNOWN).setAddress(address.toString());
      return builder.build();
    }

    /**
     * Logs the client header.
     */
    public void logClientHeader(
        long seqId,
        String serviceName,
        String methodName,
        @Nullable String authority,
        @Nullable Duration timeout,
        Metadata metadata,
        GrpcLogRecord.EventLogger eventLogger,
        @Nullable SocketAddress peerAddress) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");
      checkArgument(
          peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_SERVER,
          "peerAddress can only be specified by server");

      GrpcLogRecord.Metadata.Builder metadataProto = createMetadataProto(metadata);
      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
          .setMetadata(metadataProto);
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
    public void logServerHeader(
        long seqId,
        String serviceName,
        String methodName,
        Metadata metadata,
        GrpcLogRecord.EventLogger eventLogger,
        @Nullable SocketAddress peerAddress) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");
      checkArgument(
          peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_CLIENT,
          "peerAddress can only be specified for client");

      GrpcLogRecord.Metadata.Builder metadataProto = createMetadataProto(metadata);
      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(EventType.GRPC_CALL_RESPONSE_HEADER)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
          .setMetadata(metadataProto);
      if (peerAddress != null) {
        logEntryBuilder.setPeerAddress(socketToProto(peerAddress));
      }
      logger.log(Level.FINE, "Writing Response Header to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

    /**
     * Logs the server trailer.
     */
    public void logTrailer(
        long seqId,
        String serviceName,
        String methodName,
        Status status,
        Metadata metadata,
        GrpcLogRecord.EventLogger eventLogger,
        @Nullable SocketAddress peerAddress) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");
      checkNotNull(status, "status");
      checkArgument(
          peerAddress == null || eventLogger == GrpcLogRecord.EventLogger.LOGGER_CLIENT,
          "peerAddress can only be specified for client");

      GrpcLogRecord.Metadata.Builder metadataProto = createMetadataProto(metadata);
      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(EventType.GRPC_CALL_TRAILER)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG)
          .setMetadata(metadataProto)
          .setStatusCode(status.getCode().value());
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
    public <T> void logRpcMessage(
        long seqId,
        String serviceName,
        String methodName,
        GrpcLogRecord.EventType eventType,
        T message,
        GrpcLogRecord.EventLogger eventLogger) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");
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

      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(eventType)
          .setEventLogger(eventLogger)
          .setPayloadSize(1)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG);
      logger.log(Level.FINE, "Writing RPC message to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

    /**
     * Logs half close.
     */
    public void logHalfClose(
        long seqId,
        String serviceName,
        String methodName,
        GrpcLogRecord.EventLogger eventLogger) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");

      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(EventType.GRPC_CALL_HALF_CLOSE)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG);
      logger.log(Level.FINE, "Writing Half Close event to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

    /**
     * Logs cancellation.
     */
    public void logCancel(
        long seqId,
        String serviceName,
        String methodName,
        GrpcLogRecord.EventLogger eventLogger) {
      checkNotNull(serviceName, "serviceName");
      checkNotNull(methodName, "methodName");

      GrpcLogRecord.Builder logEntryBuilder = newTimestampBuilder()
          .setSequenceId(seqId)
          .setServiceName(serviceName)
          .setMethodName(methodName)
          .setEventType(EventType.GRPC_CALL_CANCEL)
          .setEventLogger(eventLogger)
          .setLogLevel(LogLevel.LOG_LEVEL_DEBUG);
      logger.log(Level.FINE, "Writing Cancel event to GcpLogSink");
      sink.write(logEntryBuilder.build());
    }

  }

  /**
   *  Retrieves socket address.
   */
  public static SocketAddress getPeerSocket(Attributes streamAttributes) {
    return streamAttributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
  }

  /**
   * Checks deadline for timeout.
   */
  public static Deadline min(@Nullable Deadline deadline0, @Nullable Deadline deadline1) {
    if (deadline0 == null) {
      return deadline1;
    }
    if (deadline1 == null) {
      return deadline0;
    }
    return deadline0.minimum(deadline1);
  }

}
