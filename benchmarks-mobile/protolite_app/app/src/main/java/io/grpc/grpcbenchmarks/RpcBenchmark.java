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
 * Created by davidcao on 6/30/16.
 */
public class RpcBenchmark {

    String title;
    String description;
    int methodNumber;

    public RpcBenchmark(String title, String description, int methodNumber) {
        this.title = title;
        this.description = description;
        this.methodNumber = methodNumber;
    }

    public RpcBenchmarkResult run(boolean useOkHttp, String urlString, String payloadString,
                                  String gzip) throws Exception {
        switch (methodNumber) {
            case 0:
                String address = "--address=" + urlString + ":50051";
                String[] args = {address, "--channels=1", "--outstanding_rpcs=1",
                        "--client_payload=" + payloadString, "--server_payload=" + payloadString};
                ClientConfiguration.Builder configBuilder = ClientConfiguration.newBuilder(
                        ADDRESS, CHANNELS, OUTSTANDING_RPCS, CLIENT_PAYLOAD, SERVER_PAYLOAD,
                        TLS, TESTCA, USE_DEFAULT_CIPHERS, TRANSPORT, DURATION, WARMUP_DURATION,
                        DIRECTEXECUTOR, SAVE_HISTOGRAM, STREAMING_RPCS);
                ClientConfiguration config = configBuilder.build(args);
                AsyncClient client = new AsyncClient(config);
                return client.run();
            case 1:
                int payloadSize = Integer.parseInt(payloadString);
                boolean useGzip = Boolean.parseBoolean(gzip);
                AsyncJsonClient jsonClient = new AsyncJsonClient(new URL("http://" + urlString +
                        ":50052/postPayload"), payloadSize, useGzip);
                return jsonClient.run(useOkHttp);
            default:
                throw new IllegalArgumentException("Invalid method number/tag was" +
                        " used for RpcBenchmark!");
        }
    }
}
