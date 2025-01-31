/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.internal;

import java.security.Principal;
import java.security.cert.Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

/** A no-op ssl session, to facilitate overriding only the required methods in specific
 * implementations.
 */
public class NoopSslSession implements SSLSession {
  @Override
  public byte[] getId() {
    return new byte[0];
  }

  @Override
  public SSLSessionContext getSessionContext() {
    return null;
  }

  @Override
  @SuppressWarnings("deprecation")
  public javax.security.cert.X509Certificate[] getPeerCertificateChain() {
    throw new UnsupportedOperationException("This method is deprecated and marked for removal. "
            + "Use the getPeerCertificates() method instead.");
  }

  @Override
  public long getCreationTime() {
    return 0;
  }

  @Override
  public long getLastAccessedTime() {
    return 0;
  }

  @Override
  public void invalidate() {
  }

  @Override
  public boolean isValid() {
    return false;
  }

  @Override
  public void putValue(String s, Object o) {
  }

  @Override
  public Object getValue(String s) {
    return null;
  }

  @Override
  public void removeValue(String s) {
  }

  @Override
  public String[] getValueNames() {
    return new String[0];
  }

  @Override
  public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
    return new Certificate[0];
  }

  @Override
  public Certificate[] getLocalCertificates() {
    return new Certificate[0];
  }

  @Override
  public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
    return null;
  }

  @Override
  public Principal getLocalPrincipal() {
    return null;
  }

  @Override
  public String getCipherSuite() {
    return null;
  }

  @Override
  public String getProtocol() {
    return null;
  }

  @Override
  public String getPeerHost() {
    return null;
  }

  @Override
  public int getPeerPort() {
    return 0;
  }

  @Override
  public int getPacketBufferSize() {
    return 0;
  }

  @Override
  public int getApplicationBufferSize() {
    return 0;
  }
}
