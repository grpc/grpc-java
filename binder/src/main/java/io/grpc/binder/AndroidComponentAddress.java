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

import android.content.ComponentName;
import android.content.Context;
import io.grpc.ExperimentalApi;
import java.net.SocketAddress;

/** Custom SocketAddress class referencing an Android Component. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class AndroidComponentAddress extends SocketAddress {

  private static final long serialVersionUID = 0L;

  private final ComponentName component;

  private AndroidComponentAddress(ComponentName component) {
    this.component = component;
  }

  /** Create an address for the given context instance. */
  public static AndroidComponentAddress forContext(Context context) {
    return forLocalComponent(context, context.getClass());
  }

  /** Create an address referencing a component within this application. */
  public static AndroidComponentAddress forLocalComponent(Context context, Class<?> cls) {
    return forComponent(new ComponentName(context, cls));
  }

  /** Create an address referencing a component (potentially) in another application. */
  public static AndroidComponentAddress forRemoteComponent(String packageName, String className) {
    return forComponent(new ComponentName(packageName, className));
  }

  public static AndroidComponentAddress forComponent(ComponentName component) {
    return new AndroidComponentAddress(component);
  }

  public String getAuthority() {
    return component.getPackageName();
  }

  public ComponentName getComponent() {
    return component;
  }

  @Override
  public int hashCode() {
    return component.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof AndroidComponentAddress) {
      AndroidComponentAddress that = (AndroidComponentAddress) obj;
      return component.equals(that.component);
    }
    return false;
  }

  @Override
  public String toString() {
    return "AndroidComponentAddress[" + component + "]";
  }
}
