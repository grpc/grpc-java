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

package io.grpc.internal;

/**
 * A logical {@link ClientStream} that does internal transfer processing of the clint requests.
 */
abstract class TransferableClientStream implements ClientStream {

  /**
   * Provides the place to define actions at the point when transfer is done.
   * Call this method to trigger those transfer completion activities. No-op by default.
   */
  public void onTransferComplete() {
  }
}
