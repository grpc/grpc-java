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

package io.grpc;

/**
 * A <i>Detachable</i> encapsulates an object that can be forked with underlying resources
 * detached and transferred to a new instance. The forked instance takes over the ownership
 * of resources and is responsible for releasing after use. The forked instance preserves
 * states of detached resources. Resources can be consumed through the forked instance as if
 * being continually consumed through the original instance. The original instance discards
 * states of detached resources and is no longer consumable as if the resources are exhausted.
 */
@ExperimentalApi("TODO")
public interface Detachable<T> {

  /**
   * Fork a new instance of {@code T} with underlying resources detached from this instance and
   * transferred to the new instance.
   *
   * @throws IllegalStateException if the underlying resources have already been detached.
   */
  public T detach();
}
