/*
 * Copyright 2022 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.ComponentName;
import android.content.Context;
import androidx.annotation.MainThread;
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import javax.annotation.Nullable;

/** Additional arbitrary arguments to establish a Android binder connection channel. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
public final class BinderChannelCredentials extends ChannelCredentials {

  /**
   * Creates the default BinderChannelCredentials.
   *
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @return a BinderChannelCredentials
   */
  public static BinderChannelCredentials forDefault(Context sourceContext) {
    return new BinderChannelCredentials(checkNotNull(sourceContext, "sourceContext"), null);
  }

  /**
   * Creates a BinderChannelCredentials to be used with DevicePolicyManager API.
   *
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @param devicePolicyAdminComponentName the admin component to be specified with
   *     DevicePolicyManager.bindDeviceAdminServiceAsUser API.
   * @return a BinderChannelCredentials
   */
  public static BinderChannelCredentials forDevicePolicyAdmin(
      Context sourceContext, ComponentName devicePolicyAdminComponentName) {
    return new BinderChannelCredentials(
      checkNotNull(sourceContext, "sourceContext"), devicePolicyAdminComponentName);
  }

  // The sourceContext field is intentionally not guarded, since (aside from the constructor),
  // it is only modified in the main thread.
  @Nullable private Context sourceContext;  // Only null in the unbound state.
  @Nullable private final ComponentName devicePolicyAdminComponentName;

  private BinderChannelCredentials(
      Context sourceContext, @Nullable ComponentName devicePolicyAdminComponentName) {
    this.sourceContext = sourceContext;
    this.devicePolicyAdminComponentName = devicePolicyAdminComponentName;
  }

  @Override
  public ChannelCredentials withoutBearerTokens() {
    return this;
  }

  @Nullable
  public Context getSourceContext() {
    return sourceContext;
  }

  @Nullable
  public ComponentName getDevicePolicyAdminComponentName() {
    return devicePolicyAdminComponentName;
  }

  @MainThread
  public void clearReferences() {
    sourceContext = null;
  }
}
