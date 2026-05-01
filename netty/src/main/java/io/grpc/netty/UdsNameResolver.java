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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Preconditions;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.StatusOr;
import io.netty.channel.unix.DomainSocketAddress;
import java.util.ArrayList;
import java.util.List;

final class UdsNameResolver extends NameResolver {
  private NameResolver.Listener2 listener;
  private final String authority;

  /**
   * Constructs a new instance of UdsNameResolver.
   *
   * @param authority authority of the 'unix:' URI to resolve, or null if target has no authority
   * @param targetPath path of the 'unix:' URI to resolve
   */
  UdsNameResolver(String authority, String targetPath, Args args) {
    // UDS is inherently local. According to https://github.com/grpc/grpc/blob/master/doc/naming.md,
    // this is expressed in the target URI either by using a blank authority, like "unix:///sock",
    // or by omitting authority completely, e.g. "unix:/sock".
    // TODO(jdcormie): Allow the explicit authority string "localhost"?
    checkArgument(isNullOrEmpty(authority), "authority not supported: %s", authority);
    this.authority = targetPath;
  }

  @Override
  public String getServiceAuthority() {
    return this.authority;
  }

  @Override
  public void start(Listener2 listener) {
    Preconditions.checkState(this.listener == null, "already started");
    this.listener = checkNotNull(listener, "listener");
    resolve();
  }

  @Override
  public void refresh() {
    resolve();
  }

  private void resolve() {
    ResolutionResult.Builder resolutionResultBuilder = ResolutionResult.newBuilder();
    List<EquivalentAddressGroup> servers = new ArrayList<>(1);
    servers.add(new EquivalentAddressGroup(new DomainSocketAddress(authority)));
    resolutionResultBuilder.setAddressesOrError(StatusOr.fromValue(servers));
    listener.onResult2(resolutionResultBuilder.build());
  }

  @Override
  public void shutdown() {}
}
