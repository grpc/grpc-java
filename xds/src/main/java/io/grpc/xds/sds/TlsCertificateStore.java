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

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.Internal;
import java.io.InputStream;

/**
 * TlsCertificate's PrivateKey and Certificate are extracted into InputStream's.
 * This is used by the gRPC SSLContext/Protocol Negotiator and is internal.
 * See {@link SecretManager} for a note on lifecycle management.
 */
@Internal
public final class TlsCertificateStore {

  private final InputStream privateKeyStream;
  private final InputStream certChainStream;

  private static InputStream getInputStreamFromDataSource(DataSource dataSource) {
    Preconditions.checkNotNull(dataSource);
    ByteString dataSourceByteString = null;
    if (dataSource.getSpecifierCase() == DataSource.SpecifierCase.INLINE_BYTES) {
      dataSourceByteString = dataSource.getInlineBytes();
    } else if (dataSource.getSpecifierCase() == DataSource.SpecifierCase.INLINE_STRING) {
      dataSourceByteString = dataSource.getInlineStringBytes();
    } else {
      throw new IllegalArgumentException(
          "dataSource of type " + dataSource.getSpecifierCase() + " not supported");
    }
    return dataSourceByteString.newInput();
  }

  /**
   * Creates the Store out of the TlsCertificate object of xDS.
   *
   * @param tlsCertificate TlsCertificate Object of xDS
   */
  public TlsCertificateStore(TlsCertificate tlsCertificate) {
    this(
        getInputStreamFromDataSource(Preconditions.checkNotNull(tlsCertificate).getPrivateKey()),
        getInputStreamFromDataSource(tlsCertificate.getCertificateChain()));
  }

  /**
   * Creates the Store out of 2 streams for the 2 certs on disk.
   *
   * @param privateKeySteam  stream representing private key on disk
   * @param certChainStream  stream representing cert on disk
   */
  public TlsCertificateStore(InputStream privateKeySteam, InputStream certChainStream) {
    this.privateKeyStream = privateKeySteam;
    this.certChainStream = certChainStream;
  }

  /**
   * getter for private key stream.
   * TODO: Lifecycle management to close streams as discussed in {@link SecretManager}
   *
   * @return inputStream representing private key
   */
  public InputStream getPrivateKeyStream() {
    return privateKeyStream;
  }

  /**
   * getter for cert key stream.
   * TODO: Lifecycle management to close streams as discussed in {@link SecretManager}
   *
   * @return  inputStream representing cert
   */
  public InputStream getCertChainStream() {
    return certChainStream;
  }
}
