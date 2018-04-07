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

package io.grpc.netty;

import io.netty.channel.Channel;

/**
 * The default implementation of {@link NettySocketSupport}.
 */
final class NettySocketSupportImpl extends NettySocketSupport {

  @Override
  public NativeSocketOptions getNativeSocketOptions(Channel ch) {
    // TODO(zpencer): if netty-epoll, use reflection to call EpollSocketChannel.tcpInfo()
    // And/or if some other low level socket support library is available, call it now.
    return null;
  }
}
