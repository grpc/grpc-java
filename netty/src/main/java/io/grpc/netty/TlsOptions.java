/*
 * Copyright 2020 The gRPC Authors
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

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;

// TlsOptions contains different options users could choose. In a nutshell, it provides three main
// features users could customize:
// 1. choose different levels of peer verification by specifying |VerificationAuthType|
// 2. provide custom peer verification check by inheriting |verifyPeerCertificate|
// 3. change the trust CA certificate bundle by inheriting |getTrustedCerts|
public abstract class TlsOptions {
  // VerificationAuthType contains set of verification levels users can choose to customize
  // their checks against its peer.
  // Note we don't have hostname check on server side. Choosing CertificateAndHostNameVerification
  // has the same effect as choosing CertificateVerification on server side, in terms of peer
  // endpoint check.
  public enum VerificationAuthType {
    // Default option: performs certificate verification and hostname verification.
    CertificateAndHostNameVerification,
    // Performs certificate verification, but skips hostname verification.
    // Users are responsible for verifying peer's identity via custom check callback.
    CertificateVerification,
    // Skips both certificate and hostname verification.
    // Users are responsible for verifying peer's identity and peer's certificate via custom
    // check callback.
    SkipAllVerification,
  }

  private VerificationAuthType verificationType;

  public TlsOptions(VerificationAuthType verificationType) {
    this.verificationType = verificationType;
  }

  public VerificationAuthType getVerificationAuthType() {
    return this.verificationType;
  }

  // used to perform custom peer authorization checking
  abstract void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
      SSLEngine engine) throws Exception;

  // used to perform trust CA certificates reloading
  abstract KeyStore getTrustedCerts() throws Exception;
}