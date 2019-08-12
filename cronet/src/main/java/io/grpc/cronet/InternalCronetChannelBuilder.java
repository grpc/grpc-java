/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.cronet;

import io.grpc.Internal;

/**
 * Internal {@link CronetChannelBuilder} accessor. This is intended for usage internal to the gRPC
 * team. If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalCronetChannelBuilder {

  // Prevent instantiation
  private InternalCronetChannelBuilder() {}

  /**
   * Sets {@link android.net.TrafficStats} tag to use when accounting socket traffic caused by this
   * channel. See {@link android.net.TrafficStats} for more information. If no tag is set (e.g. this
   * method isn't called), then Android accounts for the socket traffic caused by this channel as if
   * the tag value were set to 0.
   *
   * <p><b>NOTE:</b>Setting a tag disallows sharing of sockets with channels with other tags, which
   * may adversely effect performance by prohibiting connection sharing. In other words use of
   * multiplexed sockets (e.g. HTTP/2 and QUIC) will only be allowed if all channels have the same
   * socket tag.
   */
  public static void setTrafficStatsTag(CronetChannelBuilder builder, int tag) {
    builder.setTrafficStatsTag(tag);
  }

  /**
   * Sets specific UID to use when accounting socket traffic caused by this channel. See {@link
   * android.net.TrafficStats} for more information. Designed for use when performing an operation
   * on behalf of another application. Caller must hold {@link
   * android.Manifest.permission#MODIFY_NETWORK_ACCOUNTING} permission. By default traffic is
   * attributed to UID of caller.
   *
   * <p><b>NOTE:</b>Setting a UID disallows sharing of sockets with channels with other UIDs, which
   * may adversely effect performance by prohibiting connection sharing. In other words use of
   * multiplexed sockets (e.g. HTTP/2 and QUIC) will only be allowed if all channels have the same
   * UID set.
   */
  public static void setTrafficStatsUid(CronetChannelBuilder builder, int uid) {
    builder.setTrafficStatsUid(uid);
  }
}
