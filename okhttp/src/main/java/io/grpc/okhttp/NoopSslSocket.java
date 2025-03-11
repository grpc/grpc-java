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

package io.grpc.okhttp;

import java.io.IOException;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/** A no-op ssl socket, to facilitate overriding only the required methods in specific
 * implementations.
 */
class NoopSslSocket extends SSLSocket {
  @Override
  public String[] getSupportedCipherSuites() {
    return new String[0];
  }

  @Override
  public String[] getEnabledCipherSuites() {
    return new String[0];
  }

  @Override
  public void setEnabledCipherSuites(String[] suites) {

  }

  @Override
  public String[] getSupportedProtocols() {
    return new String[0];
  }

  @Override
  public String[] getEnabledProtocols() {
    return new String[0];
  }

  @Override
  public void setEnabledProtocols(String[] protocols) {

  }

  @Override
  public SSLSession getSession() {
    return null;
  }

  @Override
  public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {

  }

  @Override
  public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {

  }

  @Override
  public void startHandshake() throws IOException {

  }

  @Override
  public void setUseClientMode(boolean mode) {

  }

  @Override
  public boolean getUseClientMode() {
    return false;
  }

  @Override
  public void setNeedClientAuth(boolean need) {

  }

  @Override
  public boolean getNeedClientAuth() {
    return false;
  }

  @Override
  public void setWantClientAuth(boolean want) {

  }

  @Override
  public boolean getWantClientAuth() {
    return false;
  }

  @Override
  public void setEnableSessionCreation(boolean flag) {

  }

  @Override
  public boolean getEnableSessionCreation() {
    return false;
  }
}
