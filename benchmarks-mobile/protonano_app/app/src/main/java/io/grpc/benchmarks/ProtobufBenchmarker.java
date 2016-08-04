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

import com.google.protobuf.nano.CodedOutputByteBufferNano;
import com.google.protobuf.nano.MessageNano;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This class contains the actual logic for benchmarking. Each method returns
 * a BenchmarkResult.
 */
public class ProtobufBenchmarker {

    private static final long MIN_SAMPLE_TIME_MS = 2 * 1000;
    private static final long TARGET_TIME_MS = 10 * 1000;

    public static BenchmarkResult serializeProtobufToByteArray(final MessageNano message)
            throws Exception {
        final int serializedSize = message.getSerializedSize();
        return benchmark("Serialize to byte array", serializedSize, new Action() {
            @Override
            public void execute() {
                MessageNano.toByteArray(message);
            }
        });
    }

    public static BenchmarkResult serializeProtobufToByteBuffer(final MessageNano message)
            throws Exception {
        final int serializedSize = message.getSerializedSize();
        return benchmark("Serialize to CodedOutputByteBufferNano", message.getSerializedSize(),
                new Action() {
            @Override
            public void execute() throws IOException {
                message.writeTo(CodedOutputByteBufferNano.newInstance(new byte[serializedSize]));
            }
        });
    }

    public static BenchmarkResult deserializeProtobufFromByteArray(final MessageNano message)
            throws Exception {
        final int serializedSize = message.getSerializedSize();
        final byte inputData[] = MessageNano.toByteArray(message);
        return benchmark("Deserialize from byte array", serializedSize, new Action() {
            @Override
            public void execute() throws Exception {
                MessageNano.mergeFrom(message.getClass().newInstance(), inputData);
            }
        });
    }

    public static BenchmarkResult serializeJsonToByteArray(final String jsonString, boolean gzip)
            throws Exception {
        final int serializedSize = jsonString.getBytes("UTF-8").length;
        final JSONObject jsonObject = new JSONObject(jsonString);

        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
            BestGZIPOutputStream gos = new BestGZIPOutputStream(bos);
            gos.write(jsonString.getBytes("UTF-8"));
            gos.close();
            bos.close();

            BenchmarkResult res = benchmark("JSON serialize to byte array (gzip)", serializedSize,
                    new Action() {
                @Override
                public void execute() throws IOException {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
                    BestGZIPOutputStream gos = new BestGZIPOutputStream(bos);
                    gos.write(jsonObject.toString().getBytes("UTF-8"));
                    gos.close();
                    bos.close();
                    bos.toByteArray();
                }
            });
            res.compressedSize = bos.toByteArray().length;
            return res;
        } else {
            return benchmark("JSON serialize to byte array", serializedSize, new Action() {
                @Override
                public void execute() throws JSONException, UnsupportedEncodingException {
                    jsonObject.toString().getBytes("UTF-8");
                }
            });
        }
    }

    public static BenchmarkResult deserializeJsonfromByteArray(final String jsonString,
                                                               boolean gzip) throws Exception {
        final int serializedSize = jsonString.getBytes("UTF-8").length;

        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
            BestGZIPOutputStream gos = new BestGZIPOutputStream(bos);
            gos.write(jsonString.getBytes("UTF-8"));
            gos.close();
            bos.close();
            final byte[] compressedData = bos.toByteArray();

            BenchmarkResult res = benchmark("JSON deserialize from byte array (gzip)",
                    serializedSize, new Action() {
                @Override
                public void execute() throws JSONException, IOException {
                    ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
                    GZIPInputStream gis = new GZIPInputStream(bis, serializedSize);
                    byte[] inputData = new byte[serializedSize];
                    gis.read(inputData);
                    gis.close();
                    bis.close();
                    new JSONObject(new String(inputData));
                }
            });
            res.compressedSize = compressedData.length;
            return res;
        } else {
            final byte[] jsonData = jsonString.getBytes("UTF-8");
            return benchmark("JSON deserialize from byte array", serializedSize, new Action() {
                @Override
                public void execute() throws JSONException {
                    new JSONObject(new String(jsonData));
                }
            });
        }
    }

    /**
     * Given an Action, benchmarks it. Calculates how many iterations it takes for a minimum
     * sample time, and then calculates the number of iterations to for for the target
     * benchmark time.
     */
    private static BenchmarkResult benchmark(String name, long dataSize, Action action) throws Exception {
        // TODO: do an actual warmup, much more complicated than originally thought
        for (int i = 0; i < 100; ++i) {
            action.execute();
        }

        final AtomicBoolean dead = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dead.set(true);
            }
        }, MIN_SAMPLE_TIME_MS);

        int iterations = 0;
        for (; !dead.get(); ++iterations) {
            action.execute();
        }

        iterations = (int) ((TARGET_TIME_MS / MIN_SAMPLE_TIME_MS) * iterations);
        long elapsed = timeAction(action, iterations);
        System.out.println("Elapsed: " + elapsed / 1000000000L * 1024 * 1024);
        float mbps = (iterations * dataSize) / (elapsed / 1000000000f * 1024 * 1024);
        return new BenchmarkResult(name, iterations, elapsed, mbps, dataSize);
    }

    /**
     * Returns the time in nano seconds it took for an Action to run for
     * the provided number of iterations.
     */
    private static long timeAction(Action action, int iterations) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            action.execute();
        }
        long end = System.nanoTime();
        return end - start;
    }

    /**
     * Each benchmark creates an Action and puts the code to be benchmarked inside of execute.
     */
    interface Action {
        void execute() throws Exception;
    }
}
