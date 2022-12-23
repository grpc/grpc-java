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
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;

/** A TrustManagerFactory that returns a fixed list of trust managers. */
final class FixedTrustManagerFactory extends TrustManagerFactory {
  public FixedTrustManagerFactory(List<TrustManager> trustManagers) {
    super(new FixedTrustManagerFactorySpi(trustManagers), new UnhelpfulSecurityProvider(),
        "FakeAlgorithm");
  }

  private static final class FixedTrustManagerFactorySpi extends TrustManagerFactorySpi {
    private final List<TrustManager> trustManagers;

    public FixedTrustManagerFactorySpi(List<TrustManager> trustManagers) {
      this.trustManagers = Collections.unmodifiableList(new ArrayList<>(trustManagers));
    }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
      return trustManagers.toArray(new TrustManager[0]);
    }

    @Override
    protected void engineInit(KeyStore ks) {}

    @Override
    protected void engineInit(ManagerFactoryParameters spec) {}
  }
}
