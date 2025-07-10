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
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
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
   * <p>{@link UserHandle} can't reasonably be encoded in a target URI string. Instead, all {@link
   * io.grpc.NameResolverProvider}s producing {@link AndroidComponentAddress}es should let clients
   * address servers in another Android user using this argument.
   *
   * <p>Connecting to a server in a different Android user is uncommon and can only be done by a
   * "system app" client with special permissions. See {@link
   * AndroidComponentAddress.Builder#setTargetUser(UserHandle)} for details.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  public static final NameResolver.Args.Key<UserHandle> TARGET_ANDROID_USER =
      NameResolver.Args.Key.create("target-android-user");

  /**
   * Lets you override a Channel's pre-auth configuration (see {@link
   * BinderChannelBuilder#preAuthorizeServers(boolean)}) for a given {@link EquivalentAddressGroup}.
   *
   * <p>A {@link NameResolver} that discovers servers from an untrusted source like PackageManager
   * can use this to force server pre-auth and prevent abuse.
   */
  @EquivalentAddressGroup.Attr
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/12191")
  public static final Attributes.Key<Boolean> PRE_AUTH_SERVER_OVERRIDE =
      Attributes.Key.create("pre-auth-server-override");
}
