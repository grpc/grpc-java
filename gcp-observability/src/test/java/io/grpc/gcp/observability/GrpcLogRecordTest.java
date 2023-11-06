package io.grpc.gcp.observability;
import com.google.protobuf.ByteString;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.observabilitylog.v1.Payload;
import org.junit.Test;

import static org.junit.Assert.*;

public class GrpcLogRecordTest {

    @Test
    public void testGetAuthorityBytes() {
        GrpcLogRecord grpcLogRecord = GrpcLogRecord.newBuilder()
                .setAuthority("example.com:8080")
                .build();

        // Get the ByteString representation of the authority
        ByteString authorityBytes = grpcLogRecord.getAuthorityBytes();

        // Ensure that the ByteString is not null
        assertNotNull(authorityBytes);

        // Check if the authority string matches the original value
        assertEquals(grpcLogRecord.getAuthorityBytes(), authorityBytes);
    }

    @Test
    public void testHashCode() {
        GrpcLogRecord grpcLogRecord1 = GrpcLogRecord.newBuilder()
                .setCallId("exampleCallId")
                .setSequenceId(123L)
                .setType(EventType.CLIENT_HEADER)
                .setLogger(EventLogger.LOGGER_UNKNOWN)
                .setPayload(Payload.getDefaultInstance())
                .setPayloadTruncated(true)
                .setAuthority("example.com:8080")
                .setServiceName("ExampleService")
                .setMethodName("exampleMethod")
                .build();

        GrpcLogRecord grpcLogRecord2 = GrpcLogRecord.newBuilder()
                .setCallId("exampleCallId")
                .setSequenceId(123L)
                .setType(EventType.CLIENT_HEADER)
                .setLogger(EventLogger.LOGGER_UNKNOWN)
                .setPayload(Payload.getDefaultInstance())
                .setPayloadTruncated(true)
                .setAuthority("example.com:8080")
                .setServiceName("ExampleService")
                .setMethodName("exampleMethod")
                .build();

        // Verify that the hash codes of two equal instances are the same
        assertEquals(grpcLogRecord1.hashCode(), grpcLogRecord2.hashCode());
    }
}
