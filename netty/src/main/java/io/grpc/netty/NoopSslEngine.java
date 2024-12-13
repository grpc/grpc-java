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

package io.grpc.netty;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/**
 * A no-op implementation of SslEngine, to facilitate overriding only the required methods in
 * specific implementations.
 */
public class NoopSslEngine extends SSLEngine {
  @Override
  public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst)
          throws SSLException {
    return null;
  }

  @Override
  public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length)
          throws SSLException {
    return null;
  }

  @Override
  public Runnable getDelegatedTask() {
    return null;
  }

  @Override
  public void closeInbound() throws SSLException {

  }

  @Override
  public boolean isInboundDone() {
    return false;
  }

  @Override
  public void closeOutbound() {

  }

  @Override
  public boolean isOutboundDone() {
    return false;
  }

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
  public void beginHandshake() throws SSLException {

  }

  @Override
  public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
    return null;
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
