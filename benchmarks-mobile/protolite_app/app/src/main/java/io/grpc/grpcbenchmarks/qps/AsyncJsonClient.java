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

package io.grpc.grpcbenchmarks.qps;

import static io.grpc.grpcbenchmarks.qps.Utils.HISTOGRAM_MAX_VALUE;
import static io.grpc.grpcbenchmarks.qps.Utils.HISTOGRAM_PRECISION;

import android.util.Base64;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.HdrHistogram.Histogram;
import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import io.grpc.grpcbenchmarks.RpcBenchmarkResult;

/**
 * Mirror class of AsyncClient for HTTP.
 */
public class AsyncJsonClient {
    private static final Logger logger = Logger.getLogger(AsyncJsonClient.class.getName());
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long DURATION = 60 * 1000000000L;
    private static final long WARMUP_DURATION = 10 * 1000000000L;

    private URL url;
    private int clientPayload;
    private int serverPayload;
    private boolean useGzip;

    public AsyncJsonClient(URL url, int payloadSize, boolean useGzip) {
        this.url = url;
        this.clientPayload = payloadSize;
        this.serverPayload = payloadSize;
        this.useGzip = useGzip;
    }

    /**
     * Run the HTTP benchmarks.
     */
    public RpcBenchmarkResult run(boolean useOkHttp) throws IOException, JSONException {
        String simpleRequest = newJsonRequest();

        // System properties are not settable during runtime, but worth a try?
        // From https://docs.oracle.com/javase/6/docs/technotes/guides/net/http-keepalive.html
        if (!useOkHttp) {
            System.setProperty("http.keepAlive", "true");
        }

        // Run warmups for 10 seconds
        warmUp(url, simpleRequest, WARMUP_DURATION, false);

        // Run actual benchmarks
        long startTime = System.nanoTime();
        long endTime = startTime + DURATION;
        Histogram histogram;
        if (useOkHttp) {
            histogram = doBenchmarksOkHttp(url, simpleRequest, endTime);
        } else {
            histogram = doBenchmarks(url, simpleRequest, endTime);
        }
        long elapsedTime = System.nanoTime() - startTime;

        printStats(histogram, elapsedTime);

        long latency50 = histogram.getValueAtPercentile(50);
        long latency90 = histogram.getValueAtPercentile(90);
        long latency95 = histogram.getValueAtPercentile(95);
        long latency99 = histogram.getValueAtPercentile(99);
        long latency999 = histogram.getValueAtPercentile(99.9);
        long latencyMax = histogram.getValueAtPercentile(100);
        long queriesPerSecond = histogram.getTotalCount() * 1000000000L / elapsedTime;

        return new RpcBenchmarkResult(1, 1, serverPayload, clientPayload,
                latency50, latency90, latency95, latency99, latency999, latencyMax,
                queriesPerSecond);
    }

    /**
     * Warmup the benchmarks for a certain duration.
     */
    private void warmUp(URL url, String simpleRequest, long duration, boolean okHttp)
            throws IOException {
        long warmupEndTime = System.nanoTime() + duration;
        if (okHttp) {
            doBenchmarksOkHttp(url, simpleRequest, warmupEndTime);
        } else {
            doBenchmarks(url, simpleRequest, warmupEndTime);
        }
        // Tells the JVM it needs to garbage collect, but not guaranteed to do so.
        // Doesn't hurt to try!
        System.gc();
    }

    /**
     * Creates a new JSON string to be sent.
     */
    private String newJsonRequest() throws JSONException {
        JSONObject simpleRequest = new JSONObject();
        JSONObject payload = new JSONObject();
        payload.put("type", 0);
        payload.put("body", Base64.encodeToString(new byte[clientPayload], Base64.DEFAULT));

        simpleRequest.put("payload", payload);
        simpleRequest.put("type", 0);
        simpleRequest.put("responseSize", serverPayload);

        return simpleRequest.toString();
    }

    private Histogram doBenchmarks(URL url, String simpleRequest, long endTime) throws IOException {
        // TODO (davidcao): possibly some checks here if we ever have the need
        // for different types of calls (unlikely)
        Histogram histogram = new Histogram(HISTOGRAM_MAX_VALUE, HISTOGRAM_PRECISION);

        doPosts(histogram, url, simpleRequest, endTime);

        return histogram;
    }

    /**
     * Actual benchmarks for HTTP POST.
     */
    private void doPosts(Histogram histogram, URL url, String payload, long endTime)
            throws IOException {
        byte simpleRequest[] = payload.getBytes();
        HttpURLConnection connection;
        long lastCall = System.nanoTime();

        while (lastCall < endTime) {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");

            OutputStream out;
            if (useGzip) {
                out = new GZIPOutputStream(connection.getOutputStream());
            } else {
                connection.setFixedLengthStreamingMode(simpleRequest.length);
                out = new BufferedOutputStream(connection.getOutputStream());
            }
            out.write(simpleRequest);
            out.close();

            InputStream in = new BufferedInputStream(connection.getInputStream());
            // read input to simulate actual use case, this also actually speeds up requests
            IOUtils.toString(in);
            in.close();

            connection.disconnect();

            long now = System.nanoTime();
            histogram.recordValue((now - lastCall) / 1000);
            lastCall = now;
        }
    }

    /**
     * Same as above but using OkHttp.
     */
    private Histogram doBenchmarksOkHttp(URL url, String simpleRequest, long endTime)
            throws IOException {
        OkHttpClient client = new OkHttpClient();
        RequestBody body;

        if (useGzip) {
            byte payloadBytes[] = simpleRequest.getBytes();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(payloadBytes.length);
            GZIPOutputStream gos = new GZIPOutputStream(bos);
            gos.write(payloadBytes);
            gos.close();
            bos.close();
            body = RequestBody.create(JSON, bos.toByteArray());
        } else {
            body = RequestBody.create(JSON, simpleRequest);
        }

        Histogram histogram = new Histogram(HISTOGRAM_MAX_VALUE, HISTOGRAM_PRECISION);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        long lastCall = System.nanoTime();
        while (lastCall < endTime) {
            Response response = client.newCall(request).execute();
            response.body().string();

            long now = System.nanoTime();
            histogram.recordValue((now - lastCall) / 1000);
            lastCall = now;
        }

        return histogram;
    }

    private void printStats(Histogram histogram, long elapsedTime) {
        long latency50 = histogram.getValueAtPercentile(50);
        long latency90 = histogram.getValueAtPercentile(90);
        long latency95 = histogram.getValueAtPercentile(95);
        long latency99 = histogram.getValueAtPercentile(99);
        long latency999 = histogram.getValueAtPercentile(99.9);
        long latencyMax = histogram.getValueAtPercentile(100);
        long queriesPerSecond = histogram.getTotalCount() * 1000000000L / elapsedTime;

        StringBuilder values = new StringBuilder();
        values
                .append("Channels:                       ").append("TODO").append('\n')
                .append("Outstanding RPCs per Channel:   ").append("TODO").append('\n')
                .append("Server Payload Size:            ").append(serverPayload).append('\n')
                .append("Client Payload Size:            ").append(clientPayload).append('\n')
                .append("50%ile Latency (in micros):     ")
                .append(latency50).append('\n')
                .append("90%ile Latency (in micros):     ")
                .append(latency90).append('\n')
                .append("95%ile Latency (in micros):     ")
                .append(latency95).append('\n')
                .append("99%ile Latency (in micros):     ")
                .append(latency99).append('\n')
                .append("99.9%ile Latency (in micros):   ")
                .append(latency999).append('\n')
                .append("Maximum Latency (in micros):    ")
                .append(latencyMax).append('\n')
                .append("QPS:                            ").append(queriesPerSecond).append('\n');
        logger.log(Level.INFO, values.toString());
    }

}