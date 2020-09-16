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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Base class for dynamic {@link SslContextProvider}s. */
public abstract class DynamicSslContextProvider extends SslContextProvider {

  protected final List<Callback> pendingCallbacks = new ArrayList<>();
  @Nullable protected final CertificateValidationContext staticCertificateValidationContext;
  @Nullable protected SslContext sslContext;

  protected DynamicSslContextProvider(
      BaseTlsContext tlsContext, CertificateValidationContext staticCertValidationContext) {
    super(tlsContext);
    this.staticCertificateValidationContext = staticCertValidationContext;
  }

  @Nullable
  public SslContext getSslContext() {
    return sslContext;
  }

  protected abstract CertificateValidationContext generateCertificateValidationContext();

  /** Gets a server or client side SslContextBuilder. */
  protected abstract SslContextBuilder getSslContextBuilder(
      CertificateValidationContext certificateValidationContext)
      throws CertificateException, IOException, CertStoreException;

  // this gets called only when requested secrets are ready...
  protected final void updateSslContext() {
    try {
      CertificateValidationContext localCertValidationContext =
          generateCertificateValidationContext();
      SslContextBuilder sslContextBuilder = getSslContextBuilder(localCertValidationContext);
      CommonTlsContext commonTlsContext = getCommonTlsContext();
      if (commonTlsContext != null && commonTlsContext.getAlpnProtocolsCount() > 0) {
        List<String> alpnList = commonTlsContext.getAlpnProtocolsList();
        ApplicationProtocolConfig apn =
            new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                alpnList);
        sslContextBuilder.applicationProtocolConfig(apn);
      }
      List<Callback> pendingCallbacksCopy;
      SslContext sslContextCopy;
      synchronized (pendingCallbacks) {
        sslContext = sslContextBuilder.build();
        sslContextCopy = sslContext;
        pendingCallbacksCopy = clonePendingCallbacksAndClear();
      }
      makePendingCallbacks(sslContextCopy, pendingCallbacksCopy);
    } catch (Exception e) {
      onError(Status.fromThrowable(e));
      throw new RuntimeException(e);
    }
  }

  protected final void callPerformCallback(
          Callback callback, final SslContext sslContextCopy) {
    performCallback(
        new SslContextGetter() {
          @Override
          public SslContext get() {
            return sslContextCopy;
          }
        },
        callback
    );
  }

  @Override
  public final void addCallback(Callback callback) {
    checkNotNull(callback, "callback");
    // if there is a computed sslContext just send it
    SslContext sslContextCopy = null;
    synchronized (pendingCallbacks) {
      if (sslContext != null) {
        sslContextCopy = sslContext;
      } else {
        pendingCallbacks.add(callback);
      }
    }
    if (sslContextCopy != null) {
      callPerformCallback(callback, sslContextCopy);
    }
  }

  private final void makePendingCallbacks(
      SslContext sslContextCopy, List<Callback> pendingCallbacksCopy) {
    for (Callback callback : pendingCallbacksCopy) {
      callPerformCallback(callback, sslContextCopy);
    }
  }

  /** Propagates error to all the callback receivers. */
  public final void onError(Status error) {
    for (Callback callback : clonePendingCallbacksAndClear()) {
      callback.onException(error.asException());
    }
  }

  private List<Callback> clonePendingCallbacksAndClear() {
    synchronized (pendingCallbacks) {
      List<Callback> copy = ImmutableList.copyOf(pendingCallbacks);
      pendingCallbacks.clear();
      return copy;
    }
  }
}
