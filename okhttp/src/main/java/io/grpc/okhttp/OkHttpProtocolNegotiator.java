/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.okhttp;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.internal.GrpcUtil;
import io.grpc.okhttp.internal.OptionalMethod;
import io.grpc.okhttp.internal.Platform;
import io.grpc.okhttp.internal.Platform.TlsExtensionType;
import io.grpc.okhttp.internal.Protocol;
import io.grpc.okhttp.internal.Util;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * A helper class located in package com.squareup.okhttp.internal for TLS negotiation.
 */
class OkHttpProtocolNegotiator {
  private static final Logger logger = Logger.getLogger(OkHttpProtocolNegotiator.class.getName());
  private static final Platform DEFAULT_PLATFORM = Platform.get();
  private static OkHttpProtocolNegotiator NEGOTIATOR =
      createNegotiator(OkHttpProtocolNegotiator.class.getClassLoader());

  protected final Platform platform;

  @VisibleForTesting
  OkHttpProtocolNegotiator(Platform platform) {
    this.platform = checkNotNull(platform, "platform");
  }

  public static OkHttpProtocolNegotiator get() {
    return NEGOTIATOR;
  }

  /**
   * Creates corresponding negotiator according to whether on Android.
   */
  @VisibleForTesting
  static OkHttpProtocolNegotiator createNegotiator(ClassLoader loader) {
    boolean android = true;
    try {
      // Attempt to find Android 2.3+ APIs.
      loader.loadClass("com.android.org.conscrypt.OpenSSLSocketImpl");
    } catch (ClassNotFoundException e1) {
      logger.log(Level.FINE, "Unable to find Conscrypt. Skipping", e1);
      try {
        // Older platform before being unbundled.
        loader.loadClass("org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
      } catch (ClassNotFoundException e2) {
        logger.log(Level.FINE, "Unable to find any OpenSSLSocketImpl. Skipping", e2);
        android = false;
      }
    }
    return android
        ? new AndroidNegotiator(DEFAULT_PLATFORM)
        : new OkHttpProtocolNegotiator(DEFAULT_PLATFORM);
  }

  /**
   * Start and wait until the negotiation is done, returns the negotiated protocol.
   *
   * @throws IOException if an IO error was encountered during the handshake.
   * @throws RuntimeException if the negotiation completed, but no protocol was selected.
   */
  public String negotiate(
      SSLSocket sslSocket, String hostname, @Nullable List<Protocol> protocols) throws IOException {
    if (protocols != null) {
      configureTlsExtensions(sslSocket, hostname, protocols);
    }
    try {
      // Force handshake.
      sslSocket.startHandshake();

      String negotiatedProtocol = getSelectedProtocol(sslSocket);
      if (negotiatedProtocol == null) {
        throw new RuntimeException("TLS ALPN negotiation failed with protocols: " + protocols);
      }
      return negotiatedProtocol;
    } finally {
      platform.afterHandshake(sslSocket);
    }
  }

  /** Configure TLS extensions. */
  protected void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    platform.configureTlsExtensions(sslSocket, hostname, protocols);
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  public String getSelectedProtocol(SSLSocket socket) {
    return platform.getSelectedProtocol(socket);
  }

  @VisibleForTesting
  static final class AndroidNegotiator extends OkHttpProtocolNegotiator {
    // setUseSessionTickets(boolean)
    private static final OptionalMethod<Socket> SET_USE_SESSION_TICKETS =
        new OptionalMethod<>(null, "setUseSessionTickets", Boolean.TYPE);
    // setHostname(String)
    private static final OptionalMethod<Socket> SET_HOSTNAME =
        new OptionalMethod<>(null, "setHostname", String.class);
    // byte[] getAlpnSelectedProtocol()
    private static final OptionalMethod<Socket> GET_ALPN_SELECTED_PROTOCOL =
        new OptionalMethod<>(byte[].class, "getAlpnSelectedProtocol");
    // setAlpnProtocol(byte[])
    private static final OptionalMethod<Socket> SET_ALPN_PROTOCOLS =
        new OptionalMethod<>(null, "setAlpnProtocols", byte[].class);
    // byte[] getNpnSelectedProtocol()
    private static final OptionalMethod<Socket> GET_NPN_SELECTED_PROTOCOL =
        new OptionalMethod<>(byte[].class, "getNpnSelectedProtocol");
    // setNpnProtocol(byte[])
    private static final OptionalMethod<Socket> SET_NPN_PROTOCOLS =
        new OptionalMethod<>(null, "setNpnProtocols", byte[].class);

    // Non-null on Android 10.0+.
    // SSLSockets.isSupportedSocket(SSLSocket)
    private static final Method SSL_SOCKETS_IS_SUPPORTED_SOCKET;
    // SSLSockets.setUseSessionTickets(SSLSocket, boolean)
    private static final Method SSL_SOCKETS_SET_USE_SESSION_TICKET;
    // SSLParameters.setApplicationProtocols(String[])
    private static final Method SET_APPLICATION_PROTOCOLS;
    // SSLParameters.getApplicationProtocols()
    private static final Method GET_APPLICATION_PROTOCOLS;
    // SSLSocket.getApplicationProtocol()
    private static final Method GET_APPLICATION_PROTOCOL;

    // Non-null on Android 7.0+.
    // SSLParameters.setServerNames(List<SNIServerName>)
    private static final Method SET_SERVER_NAMES;
    // SNIHostName(String)
    private static final Constructor<?> SNI_HOST_NAME;

    static {
      // Attempt to find Android 10.0+ APIs.
      Method setApplicationProtocolsMethod = null;
      Method getApplicationProtocolsMethod = null;
      Method getApplicationProtocolMethod = null;
      Method sslSocketsIsSupportedSocketMethod = null;
      Method sslSocketsSetUseSessionTicketsMethod = null;
      try {
        Class<?> sslParameters = SSLParameters.class;
        setApplicationProtocolsMethod =
            sslParameters.getMethod("setApplicationProtocols", String[].class);
        getApplicationProtocolsMethod = sslParameters.getMethod("getApplicationProtocols");
        getApplicationProtocolMethod = SSLSocket.class.getMethod("getApplicationProtocol");
        Class<?> sslSockets = Class.forName("android.net.ssl.SSLSockets");
        sslSocketsIsSupportedSocketMethod =
            sslSockets.getMethod("isSupportedSocket", SSLSocket.class);
        sslSocketsSetUseSessionTicketsMethod =
            sslSockets.getMethod("setUseSessionTickets", SSLSocket.class, boolean.class);
      } catch (ClassNotFoundException e) {
        logger.log(Level.FINER, "Failed to find Android 10.0+ APIs", e);
      } catch (NoSuchMethodException e) {
        logger.log(Level.FINER, "Failed to find Android 10.0+ APIs", e);
      }
      SET_APPLICATION_PROTOCOLS = setApplicationProtocolsMethod;
      GET_APPLICATION_PROTOCOLS = getApplicationProtocolsMethod;
      GET_APPLICATION_PROTOCOL = getApplicationProtocolMethod;
      SSL_SOCKETS_IS_SUPPORTED_SOCKET = sslSocketsIsSupportedSocketMethod;
      SSL_SOCKETS_SET_USE_SESSION_TICKET = sslSocketsSetUseSessionTicketsMethod;

      // Attempt to find Android 7.0+ APIs.
      Method setServerNamesMethod = null;
      Constructor<?> sniHostNameConstructor = null;
      try {
        setServerNamesMethod = SSLParameters.class.getMethod("setServerNames", List.class);
        sniHostNameConstructor =
            Class.forName("javax.net.ssl.SNIHostName").getConstructor(String.class);
      } catch (ClassNotFoundException e) {
        logger.log(Level.FINER, "Failed to find Android 7.0+ APIs", e);
      } catch (NoSuchMethodException e) {
        logger.log(Level.FINER, "Failed to find Android 7.0+ APIs", e);
      }
      SET_SERVER_NAMES = setServerNamesMethod;
      SNI_HOST_NAME = sniHostNameConstructor;
    }

    AndroidNegotiator(Platform platform) {
      super(platform);
    }

    @Override
    public String negotiate(SSLSocket sslSocket, String hostname, List<Protocol> protocols)
        throws IOException {
      // First check if a protocol has already been selected, since it's possible that the user
      // provided SSLSocketFactory has already done the handshake when creates the SSLSocket.
      String negotiatedProtocol = getSelectedProtocol(sslSocket);
      if (negotiatedProtocol == null) {
        negotiatedProtocol = super.negotiate(sslSocket, hostname, protocols);
      }
      return negotiatedProtocol;
    }

    /**
     * Override {@link Platform}'s configureTlsExtensions for Android older than 5.0, since OkHttp
     * (2.3+) only support such function for Android 5.0+.
     *
     * <p>Note: Prior to Android Q, the standard way of accessing some Conscrypt features was to
     * use reflection to call hidden APIs. Beginning in Q, there is public API for all of these
     * features. We attempt to use the public API where possible. Otherwise, fall back to use the
     * old reflective API.
     */
    @Override
    protected void configureTlsExtensions(
        SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
      String[] protocolNames = protocolIds(protocols);
      SSLParameters sslParams = sslSocket.getSSLParameters();
      try {
        // Enable SNI and session tickets.
        // Hostname is normally validated in the builder (see checkAuthority) and it should
        // virtually always succeed. Check again here to avoid troubles (e.g., hostname with
        // underscore) enabling SNI, which works around cases where checkAuthority is disabled.
        // See b/154375837.
        if (hostname != null && isValidHostName(hostname)) {
          if (SSL_SOCKETS_IS_SUPPORTED_SOCKET != null
              && (boolean) SSL_SOCKETS_IS_SUPPORTED_SOCKET.invoke(null, sslSocket)) {
            SSL_SOCKETS_SET_USE_SESSION_TICKET.invoke(null, sslSocket, true);
          } else {
            SET_USE_SESSION_TICKETS.invokeOptionalWithoutCheckedException(sslSocket, true);
          }
          if (SET_SERVER_NAMES != null && SNI_HOST_NAME != null) {
            SET_SERVER_NAMES
                .invoke(sslParams, Collections.singletonList(SNI_HOST_NAME.newInstance(hostname)));
          } else {
            SET_HOSTNAME.invokeOptionalWithoutCheckedException(sslSocket, hostname);
          }
        }
        boolean alpnEnabled = false;
        if (GET_APPLICATION_PROTOCOL != null) {
          try {
            // If calling SSLSocket.getApplicationProtocol() throws UnsupportedOperationException,
            // the underlying provider does not implement operations for enabling
            // ALPN in the fashion of SSLParameters.setApplicationProtocols(). Fall back to
            // use old hidden methods.
            GET_APPLICATION_PROTOCOL.invoke(sslSocket);
            SET_APPLICATION_PROTOCOLS.invoke(sslParams, (Object) protocolNames);
            alpnEnabled = true;
          } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof UnsupportedOperationException) {
              logger.log(Level.FINER, "setApplicationProtocol unsupported, will try old methods");
            } else {
              throw e;
            }
          }
        }
        sslSocket.setSSLParameters(sslParams);
        // Check application protocols are configured correctly. If not, configure again with
        // old methods.
        // Workaround for Conscrypt bug: https://github.com/google/conscrypt/issues/832
        if (alpnEnabled && GET_APPLICATION_PROTOCOLS != null) {
          String[] configuredProtocols =
              (String[]) GET_APPLICATION_PROTOCOLS.invoke(sslSocket.getSSLParameters());
          if (Arrays.equals(protocolNames, configuredProtocols)) {
            return;
          }
        }
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      } catch (InstantiationException e) {
        throw new RuntimeException(e);
      }

      Object[] parameters = {Platform.concatLengthPrefixed(protocols)};
      if (platform.getTlsExtensionType() == TlsExtensionType.ALPN_AND_NPN) {
        SET_ALPN_PROTOCOLS.invokeWithoutCheckedException(sslSocket, parameters);
      }
      if (platform.getTlsExtensionType() != TlsExtensionType.NONE) {
        SET_NPN_PROTOCOLS.invokeWithoutCheckedException(sslSocket, parameters);
      } else {
        throw new RuntimeException("We can not do TLS handshake on this Android version, please"
            + " install the Google Play Services Dynamic Security Provider to use TLS");
      }
    }

    @Override
    public String getSelectedProtocol(SSLSocket socket) {
      if (GET_APPLICATION_PROTOCOL != null) {
        try {
          return (String) GET_APPLICATION_PROTOCOL.invoke(socket);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
          Throwable targetException = e.getTargetException();
          if (targetException instanceof UnsupportedOperationException) {
            logger.log(
                Level.FINER,
                "Socket unsupported for getApplicationProtocol, will try old methods");
          } else {
            throw new RuntimeException(e);
          }
        }
      }

      if (platform.getTlsExtensionType() == TlsExtensionType.ALPN_AND_NPN) {
        try {
          byte[] alpnResult =
              (byte[]) GET_ALPN_SELECTED_PROTOCOL.invokeWithoutCheckedException(socket);
          if (alpnResult != null) {
            return new String(alpnResult, Util.UTF_8);
          }
        } catch (Exception e) {
          logger.log(Level.FINE, "Failed calling getAlpnSelectedProtocol()", e);
          // In some implementations, querying selected protocol before the handshake will fail with
          // exception.
        }
      }

      if (platform.getTlsExtensionType() != TlsExtensionType.NONE) {
        try {
          byte[] npnResult =
              (byte[]) GET_NPN_SELECTED_PROTOCOL.invokeWithoutCheckedException(socket);
          if (npnResult != null) {
            return new String(npnResult, Util.UTF_8);
          }
        } catch (Exception e) {
          logger.log(Level.FINE, "Failed calling getNpnSelectedProtocol()", e);
          // In some implementations, querying selected protocol before the handshake will fail with
          // exception.
        }
      }
      return null;
    }
  }

  private static String[] protocolIds(List<Protocol> protocols) {
    List<String> result = new ArrayList<>();
    for (Protocol protocol : protocols) {
      result.add(protocol.toString());
    }
    return result.toArray(new String[0]);
  }

  @VisibleForTesting
  static boolean isValidHostName(String name) {
    // GrpcUtil.checkAuthority() depends on URI implementation, while Android's URI implementation
    // allows underscore in hostname. Manually disallow hostname with underscore to avoid troubles.
    // See b/154375837.
    if (name.contains("_")) {
      return false;
    }
    try {
      GrpcUtil.checkAuthority(name);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
