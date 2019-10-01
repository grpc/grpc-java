/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.Internal;

/**
 * TlsCertificate's PrivateKey and Certificate are extracted into ByteString's.
 * Used by the gRPC SSLContext/Protocol Negotiator and is internal.
 * See {@link SecretManager} for a note on lifecycle management.
 */
@Internal
public final class TlsCertificateStore {

  private final ByteString privateKey;
  private final ByteString certChain;

  private static ByteString getByteStringFromDataSource(DataSource dataSource) {
    checkNotNull(dataSource);
    ByteString dataSourceByteString = null;
    if (dataSource.getSpecifierCase() == DataSource.SpecifierCase.INLINE_BYTES) {
      dataSourceByteString = dataSource.getInlineBytes();
    } else if (dataSource.getSpecifierCase() == DataSource.SpecifierCase.INLINE_STRING) {
      dataSourceByteString = dataSource.getInlineStringBytes();
    } else {
      throw new UnsupportedOperationException(
          "dataSource of type " + dataSource.getSpecifierCase() + " not supported");
    }
    return dataSourceByteString;
  }

  /**
   * Creates the Store out of the TlsCertificate object of xDS.
   *
   * @param tlsCertificate TlsCertificate Object of xDS
   */
  public TlsCertificateStore(TlsCertificate tlsCertificate) {
    this(
        getByteStringFromDataSource(checkNotNull(tlsCertificate).getPrivateKey()),
        getByteStringFromDataSource(tlsCertificate.getCertificateChain()));
  }

  /**
   * Creates the Store out of 2 streams for the 2 certs on disk.
   *
   * @param privateKeySteam  stream representing private key on disk
   * @param certChain  stream representing cert on disk
   */
  public TlsCertificateStore(ByteString privateKeySteam, ByteString certChain) {
    checkNotNull(privateKeySteam);
    checkNotNull(certChain);
    this.privateKey = privateKeySteam;
    this.certChain = certChain;
  }

  /**
   * getter for private key stream.
   *
   * @return inputStream representing private key
   */
  public ByteString getPrivateKey() {
    return privateKey;
  }

  /**
   * getter for cert key stream.
   *
   * @return  inputStream representing cert
   */
  public ByteString getCertChain() {
    return certChain;
  }
}
