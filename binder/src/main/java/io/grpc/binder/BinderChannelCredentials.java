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
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import javax.annotation.Nullable;

/** Additional arbitrary arguments to establish a Android binder connection channel. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
public final class BinderChannelCredentials extends ChannelCredentials {

  /**
   * Creates the default BinderChannelCredentials.
   *
   * @return a BinderChannelCredentials
   */
  public static BinderChannelCredentials forDefault() {
    return new BinderChannelCredentials(null);
  }

  /**
   * Creates a BinderChannelCredentials to be used with DevicePolicyManager API.
   *
   * @param devicePolicyAdminComponentName the admin component to be specified with
   *     DevicePolicyManager.bindDeviceAdminServiceAsUser API.
   * @return a BinderChannelCredentials
   */
  public static BinderChannelCredentials forDevicePolicyAdmin(
      ComponentName devicePolicyAdminComponentName) {
    return new BinderChannelCredentials(devicePolicyAdminComponentName);
  }

  @Nullable private final ComponentName devicePolicyAdminComponentName;

  private BinderChannelCredentials(@Nullable ComponentName devicePolicyAdminComponentName) {
    this.devicePolicyAdminComponentName = devicePolicyAdminComponentName;
  }

  @Override
  public ChannelCredentials withoutBearerTokens() {
    return this;
  }

  /** 
   * Returns the admin component to be specified with DevicePolicyManager
   * bindDeviceAdminServiceAsUser API. 
   */
  @Nullable
  public ComponentName getDevicePolicyAdminComponentName() {
    return devicePolicyAdminComponentName;
  }
}
