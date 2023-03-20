/*
 * Copyright 2023 The gRPC Authors
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
 * This interface is used to schedule future retry attempts for a failed operation. The retry delay
 * and the number of attempt is defined by implementing classes. Implementations should assure
 * that only one future retry operation is ever scheduled at a time.
 */
public interface RetryScheduler {

  /**
   * A request to schedule a future retry (or retries) for a failed operation. Noop if an operation
   * has already been scheduled.
   */
  void schedule(Runnable retryOperation);

  /**
   * Resets the scheduler, effectively cancelling any future retry operation.
   */
  void reset();
}
