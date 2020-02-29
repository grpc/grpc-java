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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SslContextProvider is a "container" or provider of SslContext. This is used by gRPC-xds to
 * obtain an SslContext, so is not part of the public API of gRPC. This "container" may represent a
 * stream that is receiving the requested secret(s) or it could represent file-system based
 * secret(s) that are dynamic.
 */
public abstract class SslContextProvider<K> {

  private static final Logger logger = Logger.getLogger(SslContextProvider.class.getName());

  protected final boolean server;
  private final K source;

  public interface Callback {
    /** Informs callee of new/updated SslContext. */
    void updateSecret(SslContext sslContext);

    /** Informs callee of an exception that was generated. */
    void onException(Throwable throwable);
  }

  protected SslContextProvider(K source, boolean server) {
    checkNotNull(source, "source");
    this.source = source;
    this.server = server;
  }

  public K getSource() {
    return source;
  }

  CommonTlsContext getCommonTlsContext() {
    if (source instanceof UpstreamTlsContext) {
      return ((UpstreamTlsContext) source).getCommonTlsContext();
    } else if (source instanceof DownstreamTlsContext) {
      return ((DownstreamTlsContext) source).getCommonTlsContext();
    }
    return null;
  }

  /** Closes this provider and releases any resources. */
  void close() {}

  /**
   * Registers a callback on the given executor. The callback will run when SslContext becomes
   * available or immediately if the result is already available.
   */
  public abstract void addCallback(Callback callback, Executor executor);

  protected void performCallback(
      final SslContextGetter sslContextGetter, final Callback callback, Executor executor) {
    checkNotNull(sslContextGetter, "sslContextGetter");
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              SslContext sslContext = sslContextGetter.get();
              try {
                callback.updateSecret(sslContext);
              } catch (Throwable t) {
                logger.log(Level.SEVERE, "Exception from callback.updateSecret", t);
              }
            } catch (Throwable e) {
              logger.log(Level.SEVERE, "Exception from sslContextGetter.get()", e);
              callback.onException(e);
            }
          }
        });
  }

  /** Allows implementations to compute or get SslContext. */
  protected interface SslContextGetter {
    SslContext get() throws Exception;
  }
}
