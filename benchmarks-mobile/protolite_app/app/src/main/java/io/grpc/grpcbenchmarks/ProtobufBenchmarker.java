package io.grpc.grpcbenchmarks;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Created by davidcao on 6/13/16.
 */
public class ProtobufBenchmarker {
    private static final long MIN_SAMPLE_TIME_MS = 2 * 1000;
    private static final long TARGET_TIME_MS = 10 * 1000;

    public static BenchmarkResult serializeProtobufToByteArray(final MessageLite msg)
            throws Exception {
        final MessageLite clonedMessage = msg
                .getDefaultInstanceForType()
                .toBuilder()
                .mergeFrom(msg)
                .build();
        int serializedSize = msg.getSerializedSize();
        return benchmark("Serialize protobuf to byte array", serializedSize, new Action() {
            @Override
            public void execute() {
                MessageLite test = clonedMessage
                        .getDefaultInstanceForType()
                        .toBuilder()
                        .mergeFrom(clonedMessage)
                        .build();
                test.toByteArray();
            }
        });
    }

    public static BenchmarkResult serializeProtobufToCodedOutputStream(final MessageLite msg)
            throws Exception {
        final int serializedSize = msg.getSerializedSize();
        return benchmark("Serialize protobuf to CodedOutputStream", serializedSize, new Action() {
            @Override
            public void execute() throws Exception {
                CodedOutputStream cos = CodedOutputStream.newInstance(new byte[serializedSize]);
                msg.writeTo(cos);
                cos.flush();
                cos.checkNoSpaceLeft();
            }
        });
    }

    public static BenchmarkResult serializeProtobufToByteArrayOutputStream(final MessageLite msg)
            throws Exception {
        final int serializedSize = msg.getSerializedSize();
        return benchmark("serialize protobuf to ByteArrayOutputStream", serializedSize, new Action() {
            @Override
            public void execute() throws Exception {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(serializedSize);
                msg.writeTo(baos);
                baos.flush();
                baos.toByteArray();
            }
        });
    }

    public static BenchmarkResult deserializeProtobufFromByteArray(final MessageLite msg)
            throws Exception {
        final int serializeSize = msg.getSerializedSize();
        final byte[] data = msg.toByteArray();
        return benchmark("Deserialize protobuf from byte array", serializeSize, new Action() {
            @Override
            public void execute() throws Exception {
                msg.newBuilderForType().mergeFrom(data).build();
            }
        });
    }

    public static BenchmarkResult deserializeProtobufFromCodedInputStream(final MessageLite msg)
            throws Exception {
        final int serializedSize = msg.getSerializedSize();
        final byte[] data = msg.toByteArray();
        return benchmark("Deserialize protobuf from CodedInputStream",
                serializedSize,
                new Action() {
                    @Override
                    public void execute() throws Exception {
                        CodedInputStream cis = CodedInputStream.newInstance(data);
                        msg.newBuilderForType().mergeFrom(cis).build();
                    }
                });
    }

    public static BenchmarkResult deserializeProtobufFromByteArrayInputStream(final MessageLite msg)
            throws Exception {
        final int serializedSize = msg.getSerializedSize();
        final byte[] data = msg.toByteArray();
        return benchmark("Deserialize protobuf from ByteArrayInputStream",
                serializedSize,
                new Action() {
                    @Override
                    public void execute() throws Exception {
                        ByteArrayInputStream bais = new ByteArrayInputStream(data);
                        msg.newBuilderForType().mergeFrom(bais).build();
                    }
                });
    }

    public static BenchmarkResult serializeJsonToByteArray(final String jsonString,
                                                           boolean gzip) throws Exception {
        final int serializedSize = jsonString.getBytes().length;
        final JSONObject jsonObject = new JSONObject(jsonString);

        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(serializedSize);
            GZIPOutputStream gos = new GZIPOutputStream(bos);
            gos.write(jsonString.getBytes());
            gos.close();
            bos.close();

            BenchmarkResult res = benchmark("JSON serialize to byte array (gzip)",
                    serializedSize, new Action() {
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
                    // simulate reading from input
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

    private static BenchmarkResult benchmark(String name, long dataSize, Action action)
            throws Exception {
        for (int i = 0; i < 100; ++i) {
            action.execute();
        }

        System.gc();

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
