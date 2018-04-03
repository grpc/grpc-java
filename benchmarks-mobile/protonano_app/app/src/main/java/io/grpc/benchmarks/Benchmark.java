package io.grpc.benchmarks;

import com.google.protobuf.nano.MessageNano;

/**
 * Created by davidcao on 7/28/16.
 */
public class Benchmark {
    String title;
    String description;
    int methodNumber;

    Benchmark(String title, String description, int methodNumber) {
        this.title = title;
        this.description = description;
        this.methodNumber = methodNumber;
    }

    public BenchmarkResult run(MessageNano message, String jsonString, boolean useGzip)
            throws Exception
    {
        switch (methodNumber) {
            case 0:
                return ProtobufBenchmarker.serializeProtobufToByteArray(message);
            case 1:
                return ProtobufBenchmarker.serializeProtobufToByteBuffer(message);
            case 2:
                return ProtobufBenchmarker.deserializeProtobufFromByteArray(message);
            case 3:
                return ProtobufBenchmarker.serializeJsonToByteArray(jsonString, useGzip);
            case 4:
                return ProtobufBenchmarker.deserializeJsonfromByteArray(jsonString, useGzip);
            default:
                return ProtobufBenchmarker.serializeProtobufToByteArray(message);
        }
    }
}