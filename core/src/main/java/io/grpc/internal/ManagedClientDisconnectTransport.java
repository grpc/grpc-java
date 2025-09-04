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

package io.grpc.internal;

import io.grpc.Status;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A {@link ClientTransport} that has life-cycle management.
 *
 */
@ThreadSafe
public interface ManagedClientDisconnectTransport extends ClientTransport {

  /**
   * Initiates a forceful shutdown in which preexisting and new calls are closed. Existing calls
   * should be closed with the provided {@code reason}.
   */
  void shutdownNow(Status reason, DisconnectError disconnectError);
}
