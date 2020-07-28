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

package io.grpc.xds.internal.certprovider;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auth.oauth2.GoogleCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.BackoffPolicy;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Implementation of {@link CertificateProvider} for the Google Mesh CA. */
final class MeshCaCertificateProvider extends CertificateProvider {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProvider.class.getName());

  protected MeshCaCertificateProvider(DistributorWatcher watcher, boolean notifyCertUpdates,
      String meshCaUrl, String zone, long validitySeconds,
      int keySize, String alg, String signatureAlg, MeshCaChannelFactory meshCaChannelFactory,
      BackoffPolicy.Provider backoffPolicyProvider, long renewalGracePeriodSeconds,
      int maxRetryAttempts, GoogleCredentials oauth2Creds) {
    super(watcher, notifyCertUpdates);
  }

  @Override
  public void start() {
    // TODO implement
  }

  @Override
  public void close() {
    // TODO implement
  }

  /** Factory for creating channels to MeshCA sever. */
  abstract static class MeshCaChannelFactory {

    private static final MeshCaChannelFactory DEFAULT_INSTANCE =
        new MeshCaChannelFactory() {

          /** Creates a channel to the URL in the given list. */
          @Override
          ManagedChannel createChannel(String serverUri) {
            checkArgument(serverUri != null && !serverUri.isEmpty(), "serverUri is null/empty!");
            logger.log(Level.INFO, "Creating channel to {0}", serverUri);

            ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forTarget(serverUri);
            return channelBuilder.keepAliveTime(1, TimeUnit.MINUTES).build();
          }
        };

    static MeshCaChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to the server.
     */
    abstract ManagedChannel createChannel(String serverUri);
  }

  /** Factory for creating channels to MeshCA sever. */
  abstract static class Factory {
    private static final Factory DEFAULT_INSTANCE =
        new Factory() {

          @Override
          MeshCaCertificateProvider create(
              DistributorWatcher watcher,
              boolean notifyCertUpdates,
              String meshCaUrl,
              String zone,
              long validitySeconds,
              int keySize,
              String alg,
              String signatureAlg,
              MeshCaChannelFactory meshCaChannelFactory,
              BackoffPolicy.Provider backoffPolicyProvider,
              long renewalGracePeriodSeconds,
              int maxRetryAttempts,
              GoogleCredentials oauth2Creds) {
            return new MeshCaCertificateProvider(
                watcher,
                notifyCertUpdates,
                meshCaUrl,
                zone,
                validitySeconds,
                keySize,
                alg,
                signatureAlg,
                meshCaChannelFactory,
                backoffPolicyProvider,
                renewalGracePeriodSeconds,
                maxRetryAttempts,
                oauth2Creds);
          }
        };

    static Factory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract MeshCaCertificateProvider create(
        DistributorWatcher watcher,
        boolean notifyCertUpdates,
        String meshCaUrl,
        String zone,
        long validitySeconds,
        int keySize,
        String alg,
        String signatureAlg,
        MeshCaChannelFactory meshCaChannelFactory,
        BackoffPolicy.Provider backoffPolicyProvider,
        long renewalGracePeriodSeconds,
        int maxRetryAttempts,
        GoogleCredentials oauth2Creds);
  }
}
