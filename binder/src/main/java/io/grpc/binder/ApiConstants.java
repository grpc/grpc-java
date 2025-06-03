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

import android.content.Intent;
import android.os.UserHandle;
import io.grpc.ExperimentalApi;
import io.grpc.NameResolver;

/** Constant parts of the gRPC binder transport public API. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class ApiConstants {
  private ApiConstants() {}

  /**
   * The "action" part of the binding {@link Intent} that gRPC clients use by default to identify
   * themselves in a {@link android.app.Service#onBind(Intent)} call.
   */
  public static final String ACTION_BIND = "grpc.io.action.BIND";

  /**
   * Specifies the Android user in which target URIs should be resolved.
   *
   * <p>{@link UserHandle} can't reasonably be encoded in a target URI string. Instead, all
   * {@link io.grpc.NameResolverProvider}s producing {@link AndroidComponentAddress}es should let
   * clients address servers in another Android user using this argument.
   *
   * <p>See also {@link AndroidComponentAddress#getTargetUser()}.
   */
  public static final NameResolver.Args.Key<UserHandle> TARGET_ANDROID_USER =
      NameResolver.Args.Key.create("target-android-user");
}
