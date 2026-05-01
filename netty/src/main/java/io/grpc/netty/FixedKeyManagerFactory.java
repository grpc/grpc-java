/*
 * Copyright 2021 The gRPC Authors
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;

/** A KeyManagerFactory that returns a fixed list of key managers. */
final class FixedKeyManagerFactory extends KeyManagerFactory {
  public FixedKeyManagerFactory(List<KeyManager> keyManagers) {
    super(new FixedKeyManagerFactorySpi(keyManagers), new UnhelpfulSecurityProvider(),
        "FakeAlgorithm");
  }

  private static final class FixedKeyManagerFactorySpi extends KeyManagerFactorySpi {
    private final List<KeyManager> keyManagers;

    public FixedKeyManagerFactorySpi(List<KeyManager> keyManagers) {
      this.keyManagers = Collections.unmodifiableList(new ArrayList<>(keyManagers));
    }

    @Override
    protected KeyManager[] engineGetKeyManagers() {
      return keyManagers.toArray(new KeyManager[0]);
    }

    @Override
    protected void engineInit(KeyStore ks, char[] password) {}

    @Override
    protected void engineInit(ManagerFactoryParameters spec) {}
  }
}
