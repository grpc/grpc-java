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
import com.google.common.base.Supplier;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A utility class that detects proxies using {@link ProxySelector} and detects authentication
 * credentials using {@link Authenticator}.
 *
 */
public class ProxyDetectorImpl implements ProxyDetector {
  private static final Logger log = Logger.getLogger(ProxyDetectorImpl.class.getName());
  private static final AuthenticationProvider DEFAULT_AUTHENTICATOR
      = new AuthenticationProviderImpl();
  private static final Supplier<ProxySelector> DEFAULT_PROXY_SELECTOR =
      new Supplier<ProxySelector>() {
        @Override
        public ProxySelector get() {
          //TODO(spencerfang): consider using java.security.AccessController here
          return ProxySelector.getDefault();
        }
      };

  @Deprecated
  private static final String GRPC_PROXY_ENV_VAR = "GRPC_PROXY_EXP";
  // Do not hard code a ProxySelector because the global default ProxySelector can change
  private final Supplier<ProxySelector> proxySelector;
  private final AuthenticationProvider authenticationProvider;
  private final ProxyParameters override;

  // We want an HTTPS proxy, which operates on the entire data stream (See IETF rfc2817).
  static final String PROXY_SCHEME = "https";
  static final String URI_FORMAT = PROXY_SCHEME + "://%s";

  /**
   * A proxy selector that uses the global {@link ProxySelector#getDefault()} and
   * {@link ProxyDetectorImpl.AuthenticationProvider} to detect proxy parameters.
   */
  public ProxyDetectorImpl() {
    this(DEFAULT_PROXY_SELECTOR, DEFAULT_AUTHENTICATOR, System.getenv(GRPC_PROXY_ENV_VAR));
  }

  @VisibleForTesting
  ProxyDetectorImpl(
      Supplier<ProxySelector> proxySelector,
      AuthenticationProvider authenticationProvider,
      @Nullable String proxyEnvString) {
    this.proxySelector = proxySelector;
    this.authenticationProvider = authenticationProvider;
    if (proxyEnvString != null) {
      override = new ProxyParameters(overrideProxy(proxyEnvString), null, null);
    } else {
      override = null;
    }
  }

  @Override
  public ProxyParameters proxyFor(SocketAddress targetServerAddress) {
    if (override != null) {
      return override;
    }
    if (!(targetServerAddress instanceof InetSocketAddress)) {
      return null;
    }
    InetSocketAddress targetInetAddr = (InetSocketAddress) targetServerAddress;
    if (!targetInetAddr.isUnresolved()) {
      /*
       * If the address is already resolved, we can conclude that DnsNameResolver already called
       * us earlier and we determined no proxy is needed.
       */
      return null;
    }
    return detectProxy(targetInetAddr);
  }

  private ProxyParameters detectProxy(InetSocketAddress targetAddr) {
    String hostPort =
        GrpcUtil.authorityFromHostAndPort(targetAddr.getHostName(), targetAddr.getPort());
    URI uri;
    try {
      uri = new URI(String.format(URI_FORMAT, hostPort));
    } catch (final URISyntaxException e) {
      log.log(
          Level.WARNING,
          "Failed to construct URI for proxy lookup, proceeding without proxy: {0}",
          hostPort);
      return null;
    }

    List<Proxy> proxies = proxySelector.get().select(uri);
    if (proxies.size() > 1) {
      log.warning("More than 1 proxy detected, gRPC will select the first one");
    }
    Proxy proxy = proxies.get(0);

    if (proxy.type() == Proxy.Type.DIRECT) {
      return null;
    }
    InetSocketAddress proxyAddr = (InetSocketAddress) proxy.address();
    PasswordAuthentication auth = authenticationProvider.requestPasswordAuthentication(
        proxyAddr.getHostName(),
        proxyAddr.getAddress(),
        proxyAddr.getPort(),
        PROXY_SCHEME,
        "proxy authentication required by gRPC",
        null);

    if (auth == null) {
      return new ProxyParameters(proxyAddr, null, null);
    }

    try {
      //todo(spencerfang): users of ProxyParameters should clear the password when done
      return new ProxyParameters(proxyAddr, auth.getUserName(), new String(auth.getPassword()));
    } finally {
      Arrays.fill(auth.getPassword(), '0');
    }
  }

  /**
   * GRPC_PROXY_EXP is deprecated but let's maintain compatibility for now.
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
            + "be removed in a future release. Use the JVM flags "
            + "\"-Dhttps.proxyHost=HOST -Dhttps.proxyPort=PORT\" to set the https proxy for "
            + "this JVM.");
    return new InetSocketAddress(parts[0], port);
  }

  /**
   * This interface makes unit testing easier by avoiding direct calls to static methods.
   */
  interface AuthenticationProvider {
    PasswordAuthentication requestPasswordAuthentication(
        String host,
        InetAddress addr,
        int port,
        String protocol,
        String prompt,
        String scheme);
  }

  static class AuthenticationProviderImpl implements AuthenticationProvider {
    @Override
    public PasswordAuthentication requestPasswordAuthentication(
        String host,
        InetAddress addr,
        int port,
        String protocol,
        String prompt,
        String scheme) {
      //TODO(spencerfang): consider using java.security.AccessController here
      return Authenticator.requestPasswordAuthentication(
          host, addr, port, protocol, prompt, scheme);
    }
  }
}
