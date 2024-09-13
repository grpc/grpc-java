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
import io.grpc.ExperimentalApi;
import io.grpc.Grpc.ChannelAttr;

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
   * The target Android user of a binder Channel.
   *
   * <p>In multi-user Android, the target user for an Intent is unfortunately not part of the intent
   * itself. Instead, it's passed around as a separate argument wherever that Intent is needed.
   * Following suit, this implementation of the binder transport accepts the target Android user as
   * a parameter to BinderChannelBuilder -- unfortunately it's not in the target URI or the
   * SocketAddress. Instead, downstream plugins such as {@link io.grpc.NameResolver}s and {@link
   * io.grpc.LoadBalancer}s can use this attribute to obtain the Channel's target UserHandle. If the
   * attribute is not set, the Channel's target is the Android user hosting the current process (the
   * default).
   */
  @ChannelAttr
  public static final Attributes.Key<UserHandle> CHANNEL_ATTR_TARGET_USER =
      Attributes.Key.create("io.grpc.binder.CHANNEL_ATTR_TARGET_USER");
}
