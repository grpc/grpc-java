/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.grpcbenchmarks;

import com.google.protobuf.MessageLite;

/**
 * Class that is mainly used for the Activity. Runs the specified benchmark with run().
 */
public class Benchmark {
    String title;
    MethodEnum methodEnum;

    Benchmark(String title, MethodEnum methodEnum) {
        this.title = title;
        this.methodEnum = methodEnum;
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
