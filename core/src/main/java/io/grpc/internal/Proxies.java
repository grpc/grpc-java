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

import com.google.common.annotations.VisibleForTesting;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A utility class to detect which proxy, if any, should be used for a given
 * {@link InetSocketAddress}.
 */
public class Proxies {
  private static final Logger log = Logger.getLogger(Proxies.class.getName());

  @Deprecated
  private static final String GRPC_PROXY_ENV_VAR = "GRPC_PROXY_EXP";

  // We want an HTTPS proxy, which operates on the entire data stream (See IETF rfc2817).
  private static final String URI_FORMAT = "https://%s";

  /**
   * Given a target address, returns which proxy address should be used. If no proxy should be
   * used, then return null.
   */
  public static InetSocketAddress proxyFor(SocketAddress targetServerAddress) {
    return proxyFor(
        targetServerAddress,
        ProxySelector.getDefault(),
        System.getenv(GRPC_PROXY_ENV_VAR)
    );
  }

  /**
   * Helper method to achieve stateless unit tests.
   */
  @VisibleForTesting
  static InetSocketAddress proxyFor(
      SocketAddress targetServerAddress,
      ProxySelector proxySelector,
      @Nullable String proxyEnvStr
  ) {
    InetSocketAddress override = overrideProxy(proxyEnvStr);
    if (override != null) {
      return override;
    }

    return detectProxy(proxySelector, targetServerAddress);
  }

  /**
   * GRPC_PROXY_EXP is deprecated, but let's maintain compatibility for now.
   */
  private static InetSocketAddress overrideProxy(String proxyHostPort) {
    if (proxyHostPort == null) {
      return null;
    }

    String[] parts = proxyHostPort.split(":", 2);
    int port = 80;
    if (parts.length > 1) {
      port = Integer.parseInt(parts[1]);
    }
    log.warning(
        "Detected GRPC_PROXY_EXP and will honor it, but this feature will "
            + "be removed in a future release. Use java.net.ProxySelector instead."
    );
    return new InetSocketAddress(parts[0], port);
  }

  private static InetSocketAddress detectProxy(
      ProxySelector proxySelector,
      SocketAddress targetServerAddress
  ) {
    if (!(targetServerAddress instanceof InetSocketAddress)) {
      return null;
    }

    String hostName = ((InetSocketAddress) targetServerAddress).getHostName();
    URI uri;
    try {
      uri = new URI(String.format(URI_FORMAT, hostName));
    } catch (final URISyntaxException e) {
      log.warning(String.format(
          "Failed to construct URI for proxy lookup, proceeding without proxy: %s", hostName)
      );
      return null;
    }

    List<Proxy> proxies = proxySelector.select(uri);
    if (proxies.size() > 1) {
      log.warning("More than 1 proxy detected, gRPC will select the first one");
    }
    final Proxy proxy = proxies.get(0);

    if (proxy.type() == Proxy.Type.DIRECT) {
      return null;
    }

    return (InetSocketAddress) proxy.address();
  }
}
