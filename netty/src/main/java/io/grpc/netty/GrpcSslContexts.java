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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.ExperimentalApi;
import io.grpc.internal.ConscryptLoader;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.io.File;
import java.io.InputStream;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for configuring SslContext for gRPC.
 */
@SuppressWarnings("deprecation")
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
public class GrpcSslContexts {
  private static final Logger logger = Logger.getLogger(GrpcSslContexts.class.getName());

  private GrpcSslContexts() {}

  // The "h2" string identifies HTTP/2 when used over TLS
  private static final String HTTP2_VERSION = "h2";

  /*
   * List of ALPN/NPN protocols in order of preference.
   */
  private static final List<String> NEXT_PROTOCOL_VERSIONS =
      Collections.unmodifiableList(Arrays.asList(HTTP2_VERSION));

  /*
   * These configs use ACCEPT due to limited support in OpenSSL.  Actual protocol enforcement is
   * done in ProtocolNegotiators.
   */
  private static final ApplicationProtocolConfig ALPN = new ApplicationProtocolConfig(
      Protocol.ALPN,
      SelectorFailureBehavior.NO_ADVERTISE,
      SelectedListenerFailureBehavior.ACCEPT,
      NEXT_PROTOCOL_VERSIONS);

  private static final ApplicationProtocolConfig NPN = new ApplicationProtocolConfig(
      Protocol.NPN,
      SelectorFailureBehavior.NO_ADVERTISE,
      SelectedListenerFailureBehavior.ACCEPT,
      NEXT_PROTOCOL_VERSIONS);

  private static final ApplicationProtocolConfig NPN_AND_ALPN = new ApplicationProtocolConfig(
      Protocol.NPN_AND_ALPN,
      SelectorFailureBehavior.NO_ADVERTISE,
      SelectedListenerFailureBehavior.ACCEPT,
      NEXT_PROTOCOL_VERSIONS);

  private static final String SUN_PROVIDER_NAME = "SunJSSE";
  private static final String IBM_PROVIDER_NAME = "IBMJSSE2";
  private static final String OPENJSSE_PROVIDER_NAME = "OpenJSSE";
  private static final String BCJSSE_PROVIDER_NAME = "BCJSSE";

  /**
   * Creates an SslContextBuilder with ciphers and APN appropriate for gRPC.
   *
   * @see SslContextBuilder#forClient()
   * @see #configure(SslContextBuilder)
   */
  public static SslContextBuilder forClient() {
    return configure(SslContextBuilder.forClient());
  }

  /**
   * Creates an SslContextBuilder with ciphers and APN appropriate for gRPC.
   *
   * @see SslContextBuilder#forServer(File, File)
   * @see #configure(SslContextBuilder)
   */
  public static SslContextBuilder forServer(File keyCertChainFile, File keyFile) {
    return configure(SslContextBuilder.forServer(keyCertChainFile, keyFile));
  }

  /**
   * Creates an SslContextBuilder with ciphers and APN appropriate for gRPC.
   *
   * @see SslContextBuilder#forServer(File, File, String)
   * @see #configure(SslContextBuilder)
   */
  public static SslContextBuilder forServer(
      File keyCertChainFile, File keyFile, String keyPassword) {
    return configure(SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword));
  }

  /**
   * Creates an SslContextBuilder with ciphers and APN appropriate for gRPC.
   *
   * @see SslContextBuilder#forServer(InputStream, InputStream)
   * @see #configure(SslContextBuilder)
   */
  public static SslContextBuilder forServer(InputStream keyCertChain, InputStream key) {
    return configure(SslContextBuilder.forServer(keyCertChain, key));
  }

  /**
   * Creates an SslContextBuilder with ciphers and APN appropriate for gRPC.
   *
   * @see SslContextBuilder#forServer(InputStream, InputStream, String)
   * @see #configure(SslContextBuilder)
   */
  public static SslContextBuilder forServer(
      InputStream keyCertChain, InputStream key, String keyPassword) {
    return configure(SslContextBuilder.forServer(keyCertChain, key, keyPassword));
  }

  /**
   * Set ciphers and APN appropriate for gRPC. Precisely what is set is permitted to change, so if
   * an application requires particular settings it should override the options set here.
   */
  @CanIgnoreReturnValue
  public static SslContextBuilder configure(SslContextBuilder builder) {
    return configure(builder, defaultSslProvider());
  }

  /**
   * Set ciphers and APN appropriate for gRPC. Precisely what is set is permitted to change, so if
   * an application requires particular settings it should override the options set here.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
  @CanIgnoreReturnValue
  public static SslContextBuilder configure(SslContextBuilder builder, SslProvider provider) {
    switch (provider) {
      case JDK: {
        Provider jdkProvider = findJdkProvider();
        if (jdkProvider == null) {
          throw new IllegalArgumentException(
              "Could not find Jetty NPN/ALPN or Conscrypt as installed JDK providers");
        }
        return configure(builder, jdkProvider);
      }
      case OPENSSL: {
        ApplicationProtocolConfig apc;
        if (OpenSsl.isAlpnSupported()) {
          apc = NPN_AND_ALPN;
        } else {
          apc = NPN;
        }
        return builder
            .sslProvider(SslProvider.OPENSSL)
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(apc);
      }
      default:
        throw new IllegalArgumentException("Unsupported provider: " + provider);
    }
  }

  /**
   * Set ciphers and APN appropriate for gRPC. Precisely what is set is permitted to change, so if
   * an application requires particular settings it should override the options set here.
   */
  @CanIgnoreReturnValue
  public static SslContextBuilder configure(SslContextBuilder builder, Provider jdkProvider) {
    ApplicationProtocolConfig apc;
    if (SUN_PROVIDER_NAME.equals(jdkProvider.getName())) {
      // Jetty ALPN/NPN only supports one of NPN or ALPN
      if (JettyTlsUtil.isJettyAlpnConfigured()) {
        apc = ALPN;
      } else if (JettyTlsUtil.isJettyNpnConfigured()) {
        apc = NPN;
      } else if (JettyTlsUtil.isJava9AlpnAvailable()) {
        apc = ALPN;
      } else {
        throw new IllegalArgumentException(
            jdkProvider.getName() + " selected, but Java 9+ and Jetty NPN/ALPN unavailable");
      }
    } else if (IBM_PROVIDER_NAME.equals(jdkProvider.getName())
        || OPENJSSE_PROVIDER_NAME.equals(jdkProvider.getName())
        || BCJSSE_PROVIDER_NAME.equals(jdkProvider.getName())) {
      if (JettyTlsUtil.isJava9AlpnAvailable()) {
        apc = ALPN;
      } else {
        throw new IllegalArgumentException(
            jdkProvider.getName() + " selected, but Java 9+ ALPN unavailable");
      }
    } else if (ConscryptLoader.isConscrypt(jdkProvider)) {
      apc = ALPN;
      // TODO: Conscrypt triggers failures in the TrustManager.
      // https://github.com/grpc/grpc-java/issues/7765
      builder.protocols("TLSv1.2");
    } else {
      throw new IllegalArgumentException("Unknown provider; can't configure: " + jdkProvider);
    }
    return builder
        .sslProvider(SslProvider.JDK)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(apc)
        .sslContextProvider(jdkProvider);
  }

  /**
   * Returns OpenSSL if available, otherwise returns the JDK provider.
   */
  private static SslProvider defaultSslProvider() {
    if (OpenSsl.isAvailable()) {
      logger.log(Level.FINE, "Selecting OPENSSL");
      return SslProvider.OPENSSL;
    }
    Provider provider = findJdkProvider();
    if (provider != null) {
      logger.log(Level.FINE, "Selecting JDK with provider {0}", provider);
      return SslProvider.JDK;
    }
    logger.log(Level.INFO, "Java 9 ALPN API unavailable (this may be normal)");
    logger.log(Level.INFO, "netty-tcnative unavailable (this may be normal)",
        OpenSsl.unavailabilityCause());
    logger.log(Level.INFO, "Conscrypt not found (this may be normal)",
        ConscryptHolder.UNAVAILABILITY_CAUSE);
    logger.log(Level.INFO, "Jetty ALPN unavailable (this may be normal)",
        JettyTlsUtil.getJettyAlpnUnavailabilityCause());
    throw new IllegalStateException(
        "Could not find TLS ALPN provider; "
        + "no working netty-tcnative, Conscrypt, or Jetty NPN/ALPN available");
  }

  private static Provider findJdkProvider() {
    for (Provider provider : Security.getProviders("SSLContext.TLS")) {
      if (SUN_PROVIDER_NAME.equals(provider.getName())) {
        if (JettyTlsUtil.isJettyAlpnConfigured()
            || JettyTlsUtil.isJettyNpnConfigured()
            || JettyTlsUtil.isJava9AlpnAvailable()) {
          return provider;
        }
      } else if (IBM_PROVIDER_NAME.equals(provider.getName())
          || OPENJSSE_PROVIDER_NAME.equals(provider.getName())
          || BCJSSE_PROVIDER_NAME.equals(provider.getName())) {
        if (JettyTlsUtil.isJava9AlpnAvailable()) {
          return provider;
        }
      } else if (ConscryptLoader.isConscrypt(provider)) {
        return provider;
      }
    }
    if (ConscryptHolder.PROVIDER != null) {
      return ConscryptHolder.PROVIDER;
    }
    return null;
  }

  @SuppressWarnings("deprecation")
  static void ensureAlpnAndH2Enabled(
      io.netty.handler.ssl.ApplicationProtocolNegotiator alpnNegotiator) {
    checkArgument(alpnNegotiator != null, "ALPN must be configured");
    checkArgument(alpnNegotiator.protocols() != null && !alpnNegotiator.protocols().isEmpty(),
        "ALPN must be enabled and list HTTP/2 as a supported protocol.");
    checkArgument(
        alpnNegotiator.protocols().contains(HTTP2_VERSION),
        "This ALPN config does not support HTTP/2. Expected %s, but got %s'.",
        HTTP2_VERSION,
        alpnNegotiator.protocols());
  }

  private static class ConscryptHolder {
    static final Provider PROVIDER;
    static final Throwable UNAVAILABILITY_CAUSE;

    static {
      Provider provider;
      Throwable cause;
      try {
        provider = ConscryptLoader.newProvider();
        cause = null;
      } catch (Throwable t) {
        provider = null;
        cause = t;
      }
      PROVIDER = provider;
      UNAVAILABILITY_CAUSE = cause;
    }
  }
}
