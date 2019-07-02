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

package io.grpc.examples.authentication;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Executor;

/**
 * CallCredentials implementation, which carries the JWT value that will be propagated to the server
 * in the request metadata with the "Authorization" key and the "Bearer" prefix
 */
public class BearerToken extends CallCredentials {

  private String value;

  BearerToken(String value) {
    this.value = value;
  }

  @Override
  public void applyRequestMetadata(final RequestInfo requestInfo, final Executor executor,
      final MetadataApplier metadataApplier) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          Metadata headers = new Metadata();
          headers.put(Constant.AUTHORIZATION_METADATA_KEY,
              String.format("%s %s", Constant.BEARER_TYPE, value));
          metadataApplier.apply(headers);
        } catch (Throwable e) {
          metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e));
        }
      }
    });
  }

  @Override
  public void thisUsesUnstableApi() {
    // noop
  }
}
