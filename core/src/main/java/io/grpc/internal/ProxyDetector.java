/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import com.google.common.base.Objects;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * A utility class to detect which proxy, if any, should be used for a given
 * {@link java.net.SocketAddress}.
 */
public interface ProxyDetector {
  ProxyDetector DEFAULT_INSTANCE = new ProxyDetectorImpl();

  /** A proxy detector that always claims no proxy is needed, for unit test convenience. */
  ProxyDetector NOOP_INSTANCE = new ProxyDetector() {
    @Nullable
    @Override
    public ProxyParameters proxyFor(SocketAddress targetServerAddress) {
      return null;
    }
  };

  /**
   * Given a target address, returns which proxy address should be used. If no proxy should be
   * used, then return value will be null.
   */
  @Nullable ProxyParameters proxyFor(SocketAddress targetServerAddress);

  class ProxyParameters {
    public final InetSocketAddress address;
    @Nullable public final String username;
    @Nullable public final String password;

    ProxyParameters(
        InetSocketAddress address,
        @Nullable String username,
        @Nullable String password) {
      this.address = address;
      this.username = username;
      this.password = password;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ProxyParameters)) {
        return false;
      }
      ProxyParameters that = (ProxyParameters) o;
      return Objects.equal(address, that.address)
          && Objects.equal(username, that.username)
          && Objects.equal(password, that.password);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(address, username, password);
    }
  }
}
