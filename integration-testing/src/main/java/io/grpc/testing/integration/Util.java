/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc.testing.integration;

import com.google.common.base.Throwables;
import com.google.protobuf.MessageLite;

import io.grpc.Metadata;
import io.grpc.proto.ProtoUtils;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.CipherSuiteFilter;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;

import org.junit.Assert;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

/**
 * Utility methods to support integration testing.
 */
public class Util {

  public static final Metadata.Key<Messages.SimpleContext> METADATA_KEY =
      ProtoUtils.keyForProto(Messages.SimpleContext.getDefaultInstance());

  /**
   * Picks an unused port.
   */
  public static int pickUnusedPort() {
    try {
      ServerSocket serverSocket = new ServerSocket(0);
      int port = serverSocket.getLocalPort();
      serverSocket.close();
      return port;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Load a file from the resources folder.
   *
   * @param name  name of a file in src/main/resources/certs.
   */
  public static File loadCert(String name) throws IOException {
    InputStream in = Util.class.getResourceAsStream("/certs/" + name);
    File tmpFile = File.createTempFile(name, "");
    tmpFile.deleteOnExit();

    BufferedWriter writer = new BufferedWriter(new FileWriter(tmpFile));
    try {
      int b;
      while ((b = in.read()) != -1) {
        writer.write(b);
      }
    } finally {
      writer.close();
    }

    return tmpFile;
  }

  /** Assert that two messages are equal, producing a useful message if not. */
  public static void assertEquals(MessageLite expected, MessageLite actual) {
    if (expected == null || actual == null) {
      Assert.assertEquals(expected, actual);
    } else {
      if (!expected.equals(actual)) {
        // This assertEquals should always fail.
        Assert.assertEquals(expected.toString(), actual.toString());
        // But if it doesn't, then this should.
        Assert.assertEquals(expected, actual);
        Assert.fail("Messages not equal, but assertEquals didn't throw");
      }
    }
  }

  /** Assert that two lists of messages are equal, producing a useful message if not. */
  public static void assertEquals(List<? extends MessageLite> expected,
      List<? extends MessageLite> actual) {
    if (expected == null || actual == null) {
      Assert.assertEquals(expected, actual);
    } else if (expected.size() != actual.size()) {
      Assert.assertEquals(expected, actual);
    } else {
      for (int i = 0; i < expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    }
  }

  /**
   * Builds the SslContext for use by servers
   *
   * @return context
   *
   * @throws SSLException
   */
  public static SslContext buildServerSslContext() throws SSLException {
    SslProvider provider = null;
    File trustCertChainFile = null;
    TrustManagerFactory trustManagerFactory = null;

    File keyCertChainFile = null;
    File keyFile = null;
    String keyPassword = null;
    KeyManagerFactory keyManagerFactory = null;

    Iterable<String> ciphers = null;
    CipherSuiteFilter cipherFilter = IdentityCipherSuiteFilter.INSTANCE;
    ApplicationProtocolConfig apn = null;
    long sessionCacheSize = 0;
    long sessionTimeout = 0;

    try {
      keyCertChainFile = Util.loadCert("server1.pem");
      keyFile = Util.loadCert("server1.key");

      // OpenSSL provider ignores trustCertChainFile?
      provider = SslProvider.JDK;

      trustCertChainFile = Util.loadCert("ca.pem");
    } catch (IOException e) {
      throw new RuntimeException("Error building SSL configuration", e);
    }

    SslContext sslContext = SslContext.newServerContext(provider,
                                                        trustCertChainFile, trustManagerFactory,
                                                        keyCertChainFile, keyFile, keyPassword,
                                                        keyManagerFactory,
                                                        ciphers, cipherFilter,
                                                        apn,
                                                        sessionCacheSize,
                                                        sessionTimeout);

    return sslContext;
  }

  /**
   * Builds an SslContext for use by clients
   * @param useTestCa if we should use our test CA
   * @param useTestClientCert if we should use our test client certificate
   * @return client SSLContext
   * @throws SSLException
   */
  public static SslContext buildClientSslContext(boolean useTestCa, boolean useTestClientCert)
      throws SSLException {
    SslProvider provider = null;
    File trustCertChainFile = null;
    TrustManagerFactory trustManagerFactory = null;
    File keyCertChainFile = null;
    File keyFile = null;
    String keyPassword = null;
    KeyManagerFactory keyManagerFactory = null;
    Iterable<String> ciphers = null;
    CipherSuiteFilter cipherFilter = IdentityCipherSuiteFilter.INSTANCE;
    ApplicationProtocolConfig apn = null;
    long sessionCacheSize = 0;
    long sessionTimeout = 0;

    try {
      if (useTestCa) {
        trustCertChainFile = Util.loadCert("ca.pem");
      }

      if (useTestClientCert) {
        // OpenSSL provider does not support client keys??
        provider = SslProvider.JDK;

        keyCertChainFile = Util.loadCert("client.pem");
        keyFile = Util.loadCert("client.key");
        keyPassword = null;
      }
    } catch (IOException e) {
      throw new RuntimeException("Error building SSL configuration", e);
    }

    return SslContext.newClientContext(provider, trustCertChainFile, trustManagerFactory,
                                       keyCertChainFile, keyFile, keyPassword, keyManagerFactory,
                                       ciphers, cipherFilter,
                                       apn,
                                       sessionCacheSize, sessionTimeout);
  }
}
