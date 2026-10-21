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

package io.grpc.binder;

import android.os.IBinder;
import javax.annotation.Nullable;

/** A container for at most one instance of {@link IBinder}, useful as an "out parameter". */
public final class IBinderReceiver {
  @Nullable private volatile IBinder value;

  /** Constructs a new, initially empty, container. */
  public IBinderReceiver() {}

  /** Returns the contents of this container or null if it is empty. */
  @Nullable
  public IBinder get() {
    return value;
  }

  protected void set(IBinder value) {
    this.value = value;
  }
}
