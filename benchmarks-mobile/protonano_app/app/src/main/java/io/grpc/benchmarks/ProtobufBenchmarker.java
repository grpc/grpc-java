package io.grpc.benchmarks;

import com.google.protobuf.nano.CodedOutputByteBufferNano;
import com.google.protobuf.nano.MessageNano;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Created by davidcao on 5/31/16.
 * Largely taken from com.google.protobuf/benchmarks
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
        final int serializedSize = jsonString.getBytes().length;
        final JSONObject jsonObject = new JSONObject(jsonString);

        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
            GZIPOutputStream gos = new GZIPOutputStream(bos);
            gos.write(jsonString.getBytes());
            gos.close();
            bos.close();

            BenchmarkResult res = benchmark("JSON serialize to byte array (gzip)", serializedSize,
                    new Action() {
                @Override
                public void execute() throws IOException {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
                    GZIPOutputStream gos = new GZIPOutputStream(bos);
                    gos.write(jsonObject.toString().getBytes());
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
                public void execute() throws JSONException {
                    jsonObject.toString().getBytes();
                }
            });
        }
    }

    public static BenchmarkResult deserializeJsonfromByteArray(final String jsonString,
                                                               boolean gzip) throws Exception {
        final int serializedSize = jsonString.getBytes().length;

        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
            GZIPOutputStream gos = new GZIPOutputStream(bos);
            gos.write(jsonString.getBytes());
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
            final byte[] jsonData = jsonString.getBytes();
            return benchmark("JSON deserialize from byte array", serializedSize, new Action() {
                @Override
                public void execute() throws JSONException {
                    new JSONObject(new String(jsonData));
                }
            });
        }
    }

    private static BenchmarkResult benchmark(String name, long dataSize, Action action) throws Exception {
        for (int i = 0; i < 100; ++i) {
            action.execute();
        }

        int iterations = 1;
        long elapsed = timeAction(action, iterations);
        while (elapsed < MIN_SAMPLE_TIME_MS) {
            iterations *= 2;
            elapsed = timeAction(action, iterations);
        }

        iterations = (int) ((TARGET_TIME_MS / (double) elapsed) * iterations);
        elapsed = timeAction(action, iterations);
        float mbps = (iterations * dataSize) / (elapsed * 1024 * 1024 / 1000f);
        return new BenchmarkResult(name, iterations, elapsed, mbps, dataSize);
    }

    private static long timeAction(Action action, int iterations) throws Exception {
        System.gc();
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            action.execute();
        }
        long end = System.currentTimeMillis();
        return end - start;
    }

    interface Action {
        void execute() throws Exception;
    }
}
