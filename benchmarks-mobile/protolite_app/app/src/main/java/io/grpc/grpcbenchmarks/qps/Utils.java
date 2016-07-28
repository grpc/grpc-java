/*
 * Copyright 2015, Google Inc. All rights reserved.
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

import io.grpc.ManagedChannel;
import io.grpc.okhttp.OkHttpChannelBuilder;

import org.HdrHistogram.Histogram;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSocketFactory;

/**
 * Utility methods to support benchmarking classes.
 */
public final class Utils {
    private static final String UNIX_DOMAIN_SOCKET_PREFIX = "unix://";

    // The histogram can record values between 1 microsecond and 1 min.
    public static final long HISTOGRAM_MAX_VALUE = 60000000L;
    // Value quantization will be no larger than 1/10^3 = 0.1%.
    public static final int HISTOGRAM_PRECISION = 3;

    private Utils() {
    }

    public static boolean parseBoolean(String value) {
        return value.isEmpty() || Boolean.parseBoolean(value);
    }

    /**
     * Parse a {@link SocketAddress} from the given string.
     */
    public static SocketAddress parseSocketAddress(String value) {
        if (value.startsWith(UNIX_DOMAIN_SOCKET_PREFIX)) {
            throw new IllegalArgumentException("Must use a standard TCP/IP address");
        } else {
            // Standard TCP/IP address.
            String[] parts = value.split(":", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException(
                        "Address must be a unix:// path or be in the form host:port. Got: " + value);
            }
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            return new InetSocketAddress(host, port);
        }
    }

    /**
     * Create a {@link ManagedChannel} for the given parameters.
     */
    public static ManagedChannel newClientChannel(Transport transport, SocketAddress address,
                                                  boolean tls, @Nullable String authorityOverride,
                                                  boolean directExecutor) throws IOException {
        if (transport == Transport.OK_HTTP) {
            InetSocketAddress addr = (InetSocketAddress) address;
            OkHttpChannelBuilder builder = OkHttpChannelBuilder
                    .forAddress(addr.getHostName(), addr.getPort());
            if (directExecutor) {
                builder.directExecutor();
            }
            builder.negotiationType(tls ? io.grpc.okhttp.NegotiationType.TLS
                    : io.grpc.okhttp.NegotiationType.PLAINTEXT);
            if (tls) {
                SSLSocketFactory factory;
                factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                builder.sslSocketFactory(factory);
            }
            if (authorityOverride != null) {
                builder.overrideAuthority(authorityOverride);
            }
            return builder.build();
        }
        throw new IllegalArgumentException("Unsupported transport (Only use OK_HTTP): " + transport);
    }

    /**
     * Save a {@link Histogram} to a file.
     */
    public static void saveHistogram(Histogram histogram, String filename) throws IOException {
        File file;
        PrintStream log = null;
        try {
            file = new File(filename);
            if (file.exists() && !file.delete()) {
                System.err.println("Failed deleting previous histogram file: " + file.getAbsolutePath());
            }
            log = new PrintStream(new FileOutputStream(file), false);
            histogram.outputPercentileDistribution(log, 1.0);
        } finally {
            if (log != null) {
                log.close();
            }
        }
    }
}
