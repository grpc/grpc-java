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

/**
 * Returned by a benchmark method, provides results of the benchmark.
 */
public class BenchmarkResult {
    public String name;
    // number of iterations in the target time
    public int iterations;
    // elapsed time in nano seconds
    public long elapsed;
    // speed of benchmark in MBps
    public float mbps;
    // size of message that was serialized in bytes
    public long size;
    // compressed size of message if compression was used
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
        return name + ": serialized size: " + size + "bytes"
                + (compressedSize != 0 ? " (" + compressedSize + "bytes gzipped), " : ", ")
                + iterations + " iterations in " + (elapsed / 1000000000f)
                + "s, ~" + mbps + "Mb/s.";
    }
}