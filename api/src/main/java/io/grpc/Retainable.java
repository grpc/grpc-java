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
 * A <i>Retainable</i> is a resource that can be retained, preventing it being closed too early.
 *
 * <p>A normal usage of this API is to take over the lifetime management of some resource,
 * prevent it being closed or reclaimed by its original owner. This can be used to extend the
 * lifetime of some resource not held by the current consumer.
 */
@ExperimentalApi("TODO")
public interface Retainable {

  /**
   * Prevents the resource from being closed and reclaimed, until {@link #release} is called.
   * After this call, the caller effectively becomes the owner of the resource and
   * responsible for managing the lifetime.
   *
   * <p>Calling this method on an already retained or closed resource is a no-op.
   */
  public void retain();

  /**
   * Releases the hold for preventing tbe underlying resource from being closed and reclaimed.
   * Note calling this method does not automatically close the underlying resource, callers are
   * still responsible for closing it. Otherwise, the underlying resource may be leaked.
   *
   * <p>Calling this method on an unretained or already reclaimed resource is a no-op.
   */
  public void release();
}
