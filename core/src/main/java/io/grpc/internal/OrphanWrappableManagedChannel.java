/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import io.grpc.ManagedChannel;
import java.lang.ref.Reference;

/**
 * An ManagedChannel that can be wrapped by {@link ManagedChannelOrphanWrapper}.
 */
abstract class OrphanWrappableManagedChannel extends ManagedChannel {

  /**
   * Registers a reference that should be cleared when this object is cleanly
   * shut down and is no longer a candidate for being orphaned.
   */
  abstract void setPhantom(Reference<?> phantom);
}
