/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.auth;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import io.grpc.CallCredentials;
import io.grpc.Status;
import java.util.concurrent.Executor;

/**
 * {@link CallCredentials} that authenticates using the default service account
 * of a Google Compute Engine (GCE) instance.
 *
 * <p>It obtains an ID token from the GCE metadata server and uses it to create
 * {@link IdTokenCredentials} which are then adapted to {@link CallCredentials}
 * using {@link MoreCallCredentials}.
 *
 * <p>This class is intended for use on GCE instances. It will not work
 * in other environments.
 */
public class GcpServiceAccountIdentityCredentials extends CallCredentials {
  private final String audience;

  public GcpServiceAccountIdentityCredentials(String audience) {
    this.audience = audience;
  }

  @Override
  public void applyRequestMetadata(RequestInfo requestInfo,
      Executor appExecutor, MetadataApplier applier) {
    appExecutor.execute(() -> {
      try {
        CallCredentials grpcCredentials = getCallCredentials();
        grpcCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
      } catch (Exception e) {
        applier.fail(Status.UNAUTHENTICATED.withCause(e));
      }
    });
  }

  private CallCredentials getCallCredentials() throws Exception {
    ComputeEngineCredentials credentials = ComputeEngineCredentials.create();
    IdTokenCredentials idTokenCredentials = IdTokenCredentials.newBuilder()
        .setIdTokenProvider(credentials)
        .setTargetAudience(audience)
        .build();
    return MoreCallCredentials.from(idTokenCredentials);
  }

  @Override
  public String toString() {
    return "GcpServiceAccountIdentityCallCredentials{"
        + "audience='" + audience + '\'' + '}';
  }
}
