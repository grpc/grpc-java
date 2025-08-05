/*
 * Copyright 2025 The gRPC Authors
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
package io.grpc.binder.internal;

import android.content.Context;
import android.os.UserHandle;
import java.lang.reflect.Method;

/**
 * A collection of static methods that wrap hidden Android "System APIs."
 *
 * <p>grpc-java can't call Android methods marked @SystemApi directly, even though many of our users
 * are "system apps" entitled to do so. Being a library built outside the Android source tree, these
 * "non-SDK" elements simply don't exist from our compiler's perspective. Instead we resort to
 * reflection but use the static wrappers found here to keep call sites readable and type safe.
 *
 * <p>Modern Android's JRE also limits the visibility of these methods at *runtime*. Only certain
 * privileged apps installed on the system image app can call them, even using reflection, and this
 * wrapper doesn't change that. Callers are responsible for ensuring that the host app actually has
 * the ability to call @SystemApis. See
 * https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces for more.
 */
final class SystemApis {
  private static volatile Method createContextAsUserMethod;

  // Not to be instantiated.
  private SystemApis() {}

  /** Returns a new Context object whose methods act as if they were running in the given user. */
  public static Context createContextAsUser(Context context, UserHandle userHandle, int flags) {
    try {
      if (createContextAsUserMethod == null) {
        synchronized (SystemApis.class) {
          if (createContextAsUserMethod == null) {
            createContextAsUserMethod =
                Context.class.getMethod("createContextAsUser", UserHandle.class, int.class);
          }
        }
      }
      return (Context) createContextAsUserMethod.invoke(context, userHandle, flags);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Requires SDK_INT >= R and @SystemApi visibility", e);
    }
  }
}
