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

package io.grpc.alts;

import io.grpc.CallCredentials;
import java.util.concurrent.Executor;

/**
 * {@code CallCredentials} that will pick the right credentials based on whether the established
 * connection is ALTS or TLS.
 */
final class DualCallCredentials extends CallCredentials {
  private final CallCredentials tlsCallCredentials;
  private final CallCredentials altsCallCredentials;

  public DualCallCredentials(CallCredentials tlsCallCreds, CallCredentials altsCallCreds) {
    tlsCallCredentials = tlsCallCreds;
    altsCallCredentials = altsCallCreds;
  }

  @Override
  public void applyRequestMetadata(
      CallCredentials.RequestInfo requestInfo,
      Executor appExecutor,
      CallCredentials.MetadataApplier applier) {
    if (AltsContextUtil.check(requestInfo.getTransportAttrs())) {
      altsCallCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
    } else {
      tlsCallCredentials.applyRequestMetadata(requestInfo, appExecutor, applier);
    }
  }
}
