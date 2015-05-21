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

package io.grpc.benchmarks.qps;

import static io.grpc.testing.integration.Util.loadCert;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;

import io.grpc.Channel;
import io.grpc.testing.Payload;
import io.grpc.testing.SimpleRequest;
import io.grpc.transport.netty.GrpcSslContexts;
import io.grpc.transport.netty.NativeUtil;
import io.grpc.transport.netty.NegotiationType;
import io.grpc.transport.netty.NettyChannelBuilder;
import io.grpc.transport.okhttp.OkHttpChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;

import org.HdrHistogram.Histogram;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;

/**
 * Utility methods to support benchmarking classes.
 */
final class Utils {

  // The histogram can record values between 1 microsecond and 1 min.
  static final long HISTOGRAM_MAX_VALUE = 60000000L;
  // Value quantization will be no larger than 1/10^3 = 0.1%.
  static final int HISTOGRAM_PRECISION = 3;

  private Utils() {
  }

  static boolean parseBoolean(String value) {
    return value.isEmpty() || Boolean.parseBoolean(value);
  }

  static SimpleRequest newRequest(ClientConfiguration config) {
    ByteString body = ByteString.copyFrom(new byte[config.clientPayload]);
    Payload payload = Payload.newBuilder()
        .setType(config.payloadType)
        .setBody(body)
        .build();

    return SimpleRequest.newBuilder()
        .setResponseType(config.payloadType)
        .setResponseSize(config.serverPayload)
        .setPayload(payload)
        .build();
  }

  static Channel newClientChannel(ClientConfiguration config) throws IOException {
    if (config.transport == ClientConfiguration.Transport.OK_HTTP) {
      InetSocketAddress addr = (InetSocketAddress) config.address;
      return OkHttpChannelBuilder
          .forAddress(addr.getHostName(), addr.getPort())
          .executor(config.directExecutor ? MoreExecutors.newDirectExecutorService() : null)
          .build();
    }

    // It's a Netty transport.
    SslContext context = null;
    NegotiationType negotiationType = config.tls ? NegotiationType.TLS : NegotiationType.PLAINTEXT;
    if (config.tls && config.testca) {
      File cert = loadCert("ca.pem");
      boolean useJdkSsl = config.transport == ClientConfiguration.Transport.NETTY_NIO;
      context = GrpcSslContexts.forClient().trustManager(cert)
          .sslProvider(useJdkSsl ? SslProvider.JDK : SslProvider.OPENSSL)
          .build();
    }

    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(config.address)
        .negotiationType(negotiationType)
        .executor(config.directExecutor ? MoreExecutors.newDirectExecutorService() : null)
        .sslContext(context)
        .connectionWindowSize(config.connectionWindow)
        .streamWindowSize(config.streamWindow);

    switch (config.transport) {
      case NETTY_NIO:
        // Do nothing ... this is the default.
        break;
      case NETTY_EPOLL:
        NativeUtil.Transport.EPOLL.configure(builder);
        break;
      case NETTY_UNIX_DOMAIN_SOCKET:
        NativeUtil.Transport.UNIX_DOMAIN_SOCKET.configure(builder);
        break;
      default:
        // Should never get here.
        throw new IllegalArgumentException("Unsupported transport: " + config.transport);
    }
    return builder.build();
  }

  static void saveHistogram(Histogram histogram, String filename) throws IOException {
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
