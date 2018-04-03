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

import java.net.URL;

import io.grpc.grpcbenchmarks.qps.AsyncClient;
import io.grpc.grpcbenchmarks.qps.AsyncJsonClient;
import io.grpc.grpcbenchmarks.qps.ClientConfiguration;

import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.ADDRESS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.CHANNELS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.CLIENT_PAYLOAD;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.DIRECTEXECUTOR;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.DURATION;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.OUTSTANDING_RPCS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.SAVE_HISTOGRAM;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.SERVER_PAYLOAD;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.STREAMING_RPCS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.TESTCA;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.TLS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.TRANSPORT;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.USE_DEFAULT_CIPHERS;
import static io.grpc.grpcbenchmarks.qps.ClientConfiguration.ClientParam.WARMUP_DURATION;

/**
 * Wrapper class used by RpcBenchmarksActivity in order to run a benchmark.
 */
public class RpcBenchmark {

    String title;
    RpcEnum rpcEnum;

    public RpcBenchmark(String title, RpcEnum rpcEnum) {
        this.title = title;
        this.rpcEnum = rpcEnum;
    }


    public RpcBenchmarkResult run(boolean useOkHttp, String urlString, int payloadSize,
                                  boolean useGzip) throws Exception {
        switch (rpcEnum) {
            case GRPC:
                String address = "--address=" + urlString + ":50051";
                String[] args = {address, "--channels=1", "--outstanding_rpcs=1",
                        "--client_payload=" + payloadSize, "--server_payload=" + payloadSize};
                ClientConfiguration.Builder configBuilder = ClientConfiguration.newBuilder(
                        ADDRESS, CHANNELS, OUTSTANDING_RPCS, CLIENT_PAYLOAD, SERVER_PAYLOAD,
                        TLS, TESTCA, USE_DEFAULT_CIPHERS, TRANSPORT, DURATION, WARMUP_DURATION,
                        DIRECTEXECUTOR, SAVE_HISTOGRAM, STREAMING_RPCS);
                ClientConfiguration config = configBuilder.build(args);
                AsyncClient client = new AsyncClient(config);
                return client.run();
            case HTTP:
                AsyncJsonClient jsonClient = new AsyncJsonClient(new URL("http://" + urlString +
                        ":50052/postPayload"), payloadSize, useGzip);
                return jsonClient.run(useOkHttp);
            default:
                throw new IllegalArgumentException("Invalid method number/tag was" +
                        " used for RpcBenchmark!");
        }
    }
}
