package io.grpc.grpcbenchmarks;

/**
 * Created by davidcao on 6/20/16.
 */
public class RpcBenchmarkResult {
    public int channels;
    public int outstandingRPCs;
    public int serverPayload;
    public int clientPayload;
    public long latency50;
    public long latency90;
    public long latency95;
    public long latency99;
    public long latency999;
    public long latencyMax;
    public long qps;

    public RpcBenchmarkResult(int channels, int rpcs, int serverPayload, int clientPayload,
                              long latency50, long latency90, long latency95, long latency99,
                              long latency999, long latencyMax, long qps) {
        this.channels = channels;
        this.outstandingRPCs = rpcs;
        this.serverPayload = serverPayload;
        this.clientPayload = clientPayload;
        this.latency50 = latency50;
        this.latency90 = latency90;
        this.latency95 = latency95;
        this.latency99 = latency99;
        this.latency999 = latency999;
        this.latencyMax = latencyMax;
        this.qps = qps;
    }

    @Override
    public String toString() {
        StringBuilder values = new StringBuilder();
        values
                .append("Channels:                       ").append(channels).append('\n')
                .append("Outstanding RPCs per Channel:   ").append(outstandingRPCs).append('\n')
                .append("Server Payload Size:            ").append(serverPayload).append('\n')
                .append("Client Payload Size:            ").append(clientPayload).append('\n')
                .append("50%ile Latency (in micros):     ").append(latency50).append('\n')
                .append("90%ile Latency (in micros):     ").append(latency90).append('\n')
                .append("95%ile Latency (in micros):     ").append(latency95).append('\n')
                .append("99%ile Latency (in micros):     ").append(latency99).append('\n')
                .append("99.9%ile Latency (in micros):   ").append(latency999).append('\n')
                .append("Maximum Latency (in micros):    ").append(latencyMax).append('\n')
                .append("QPS:                            ").append(qps).append('\n');
        return values.toString();
    }
}
