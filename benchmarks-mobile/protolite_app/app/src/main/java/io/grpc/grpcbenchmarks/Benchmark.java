package io.grpc.grpcbenchmarks;

import com.google.protobuf.MessageLite;

/**
 * Created by davidcao on 6/13/16.
 */
public class Benchmark {
    String title;
    String description;
    MethodEnum methodEnum;
    int methodNumber;

    Benchmark(String title, String description, int methodNumber) {
        this.title = title;
        this.description = description;
        this.methodNumber = methodNumber;
    }

    public BenchmarkResult run(MessageLite msg, String json, boolean useGzip) throws Exception {
        switch (methodEnum) {
            case SERIAL_CODED_OUTPUT:
                return ProtobufBenchmarker.serializeProtobufToByteArray(msg);
            case SERIAL_BYTE_ARRAY_OUTPUT_STREAM:
                return ProtobufBenchmarker.serializeProtobufToCodedOutputStream(msg);
            case SERIAL_BYTE_ARRAY:
                return ProtobufBenchmarker.serializeProtobufToByteArrayOutputStream(msg);
            case DESERIAL_CODED_INPUT:
                return ProtobufBenchmarker.deserializeProtobufFromByteArray(msg);
            case DESERIAL_BYTE_ARRAY_INPUT:
                return ProtobufBenchmarker.deserializeProtobufFromCodedInputStream(msg);
            case DESERIAL_BYTE_ARRAY:
                return ProtobufBenchmarker.deserializeProtobufFromByteArrayInputStream(msg);
            case SERIAL_JSON_BYTE_ARRAY:
                return ProtobufBenchmarker.serializeJsonToByteArray(json, useGzip);
            case DESERIAL_JSON_BYTE_ARRAY:
                return ProtobufBenchmarker.deserializeJsonfromByteArray(json, useGzip);
            default:
                throw new IllegalArgumentException("Invalid method type. " +
                        "Did you set it correctly?");
        }
    }
}
