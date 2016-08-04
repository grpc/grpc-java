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

package io.grpc.benchmarks;

import com.google.protobuf.nano.MessageNano;

/**
 * Class that is mainly used for the Activity. Runs the specified benchmark with run().
 */
public class Benchmark {
    String title;
    int methodNumber;

    Benchmark(String title, int methodNumber) {
        this.title = title;
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