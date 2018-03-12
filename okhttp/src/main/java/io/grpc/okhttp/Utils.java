/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.okhttp;

import com.google.common.base.Preconditions;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.internal.Channelz;
import io.grpc.internal.TransportFrameUtil;
import io.grpc.okhttp.internal.CipherSuite;
import io.grpc.okhttp.internal.ConnectionSpec;
import io.grpc.okhttp.internal.framed.Header;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;

/**
 * Common utility methods for OkHttp transport.
 */
class Utils {
  static final int DEFAULT_WINDOW_SIZE = 65535;
  static final int CONNECTION_STREAM_ID = 0;

  public static Metadata convertHeaders(List<Header> http2Headers) {
    return InternalMetadata.newMetadata(convertHeadersToArray(http2Headers));
  }

  public static Metadata convertTrailers(List<Header> http2Headers) {
    return InternalMetadata.newMetadata(convertHeadersToArray(http2Headers));
  }

  private static byte[][] convertHeadersToArray(List<Header> http2Headers) {
    byte[][] headerValues = new byte[http2Headers.size() * 2][];
    int i = 0;
    for (Header header : http2Headers) {
      headerValues[i++] = header.name.toByteArray();
      headerValues[i++] = header.value.toByteArray();
    }
    return TransportFrameUtil.toRawSerializedHeaders(headerValues);
  }

  /**
   * Converts an instance of {@link com.squareup.okhttp.ConnectionSpec} for a secure connection into
   * that of {@link ConnectionSpec} in the current package.
   *
   * @throws IllegalArgumentException
   *         If {@code spec} is not with TLS
   */
  static ConnectionSpec convertSpec(com.squareup.okhttp.ConnectionSpec spec) {
    Preconditions.checkArgument(spec.isTls(), "plaintext ConnectionSpec is not accepted");

    List<com.squareup.okhttp.TlsVersion> tlsVersionList = spec.tlsVersions();
    String[] tlsVersions = new String[tlsVersionList.size()];
    for (int i = 0; i < tlsVersions.length; i++) {
      tlsVersions[i] = tlsVersionList.get(i).javaName();
    }

    List<com.squareup.okhttp.CipherSuite> cipherSuiteList = spec.cipherSuites();
    CipherSuite[] cipherSuites = new CipherSuite[cipherSuiteList.size()];
    for (int i = 0; i < cipherSuites.length; i++) {
      cipherSuites[i] = CipherSuite.valueOf(cipherSuiteList.get(i).name());
    }

    return new ConnectionSpec.Builder(spec.isTls())
        .supportsTlsExtensions(spec.supportsTlsExtensions())
        .tlsVersions(tlsVersions)
        .cipherSuites(cipherSuites)
        .build();
  }

  /**
   * Attempts to capture all known socket options and return the results as a
   * {@link Channelz.SocketOptions}. If getting a socket option threw an exception,
   * log the error to the output object and move on to the next option.
   */
  static Channelz.SocketOptions getSocketOptions(Socket socket) {
    Channelz.SocketOptions.Builder builder = new Channelz.SocketOptions.Builder();
    try {
      builder.setSocketOptionLingerSeconds(socket.getSoLinger());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_LINGER", exceptionToString(e));
    }

    try {
      builder.setSocketOptionTimeoutMillis(socket.getSoTimeout());
    } catch (Exception e) {
      builder.addOption("channelzerror:SO_TIMEOUT", exceptionToString(e));
    }

    try {
      builder.addOption("TCP_NODELAY", socket.getTcpNoDelay());
    } catch (SocketException e) {
      builder.addOption("channelzerror:TCP_NODELAY", exceptionToString(e));
    }

    try {
      builder.addOption("SO_REUSEADDR", socket.getReuseAddress());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_REUSEADDR", exceptionToString(e));
    }

    try {
      builder.addOption("SO_SNDBUF", socket.getSendBufferSize());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_SNDBUF", exceptionToString(e));
    }

    try {
      builder.addOption("SO_RECVBUF", socket.getReceiveBufferSize());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_RECVBUF", exceptionToString(e));
    }

    try {
      builder.addOption("SO_KEEPALIVE", socket.getKeepAlive());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_KEEPALIVE", exceptionToString(e));
    }

    try {
      builder.addOption("SO_OOBINLINE", socket.getOOBInline());
    } catch (SocketException e) {
      builder.addOption("channelzerror:SO_OOBINLINE", exceptionToString(e));
    }

    try {
      builder.addOption("IP_TOS", socket.getTrafficClass());
    } catch (SocketException e) {
      builder.addOption("channelzerror:IP_TOS", exceptionToString(e));
    }
    return builder.build();
  }

  private static String exceptionToString(Exception e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  private Utils() {
    // Prevents instantiation
  }
}
