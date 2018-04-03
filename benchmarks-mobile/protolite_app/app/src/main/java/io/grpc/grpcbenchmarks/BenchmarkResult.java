package io.grpc.grpcbenchmarks;

/**
 * Created by davidcao on 6/13/16.
 */
public class BenchmarkResult {
    public String name;
    public int iterations;
    public long elapsed;
    public float mbps;
    public long size;
    public long compressedSize;

    public BenchmarkResult(String name, int iters, long elapsed, float mbps, long size) {
        this.name = name;
        this.iterations = iters;
        this.elapsed = elapsed;
        this.mbps = mbps;
        this.size = size;
        this.compressedSize = 0;
    }

    @Override
    public String toString() {
        return "Serialized size: " + size + "bytes"
                + (compressedSize != 0 ? " (" + compressedSize + "bytes gzipped), " : ", ")
                + iterations + " iterations in " + (elapsed / 1000f)
                + "s, ~" + mbps + "Mb/s.";
    }

}
