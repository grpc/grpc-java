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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *  Implementation of a file based secret provider.
 */
final class TlsCertificateSecretVolumeSecretProvider
    implements SecretProvider<TlsCertificateStore> {

  private static final Logger logger = Logger
      .getLogger(TlsCertificateSecretVolumeSecretProvider.class.getName());
  public static final String PEM = ".pem";
  public static final String CRT = ".crt";

  private final String path;

  // for now mark it unused
  @SuppressWarnings("unused")
  private final String name;

  TlsCertificateSecretVolumeSecretProvider(String path, String name) {
    this.path = path;
    this.name = name;
  }

  @Override
  public void addListener(Runnable listener, Executor executor) {
    checkNotNull(listener, "listener");
    checkNotNull(executor, "executor");
    try {
      executor.execute(listener);
    } catch (RuntimeException e) {
      // ListenableFuture's contract is that it will not throw unchecked exceptions, so log the bad
      // runnable and/or executor and swallow it.
      logger.log(
          Level.SEVERE,
          "RuntimeException while executing runnable " + listener + " with executor " + executor,
          e);
    }
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  /**
   * Gets the current contents of the private key and cert file. Assume the key has
   * <literal>.pem</literal> extension and cert has <literal>.crt</literal> extension
   * (needs to match mounted secrets).
   */
  @Override
  public TlsCertificateStore get() throws ExecutionException {
    try {
      final FileInputStream pemStream = new FileInputStream(path + PEM);
      final FileInputStream crtStream = new FileInputStream(path + CRT);
      return new TlsCertificateStore(ByteString.readFrom(pemStream),
          ByteString.readFrom(crtStream));
    } catch (IOException e) {
      throw new ExecutionException(e);
    }
  }

  /**
   * The file based secret provider does not need to wait (reads the current files as per
   * the contract) so we ignore the timeout.
   */
  @Override
  public TlsCertificateStore get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    checkNotNull(unit);
    return get();
  }

}
