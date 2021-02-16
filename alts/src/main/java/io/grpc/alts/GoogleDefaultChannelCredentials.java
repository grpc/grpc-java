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

package io.grpc.alts;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.alts.internal.AltsProtocolNegotiator.GoogleDefaultProtocolNegotiatorFactory;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalNettyChannelCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLException;

/**
 * Credentials appropriate to contact Google services. This class sets up a secure channel using
 * ALTS if applicable and uses TLS as fallback.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
public final class GoogleDefaultChannelCredentials {
  private static Logger logger = Logger.getLogger(GoogleDefaultChannelCredentials.class.getName());

  private GoogleDefaultChannelCredentials() {}

  /**
   * Creates Google default credentials uses a secure channel with ALTS if applicable and uses TLS
   * as fallback.
   */
  public static ChannelCredentials create() {
    ChannelCredentials nettyCredentials =
        InternalNettyChannelCredentials.create(createClientFactory());
    CallCredentials callCredentials;
    try {
      callCredentials = MoreCallCredentials.from(GoogleCredentials.getApplicationDefault());
    } catch (IOException e) {
      // TODO(ejona): Should this just throw?
      callCredentials = new FailingCallCredentials(
          Status.UNAUTHENTICATED
              .withDescription("Failed to get Google default credentials")
              .withCause(e));
    }
    return CompositeChannelCredentials.create(nettyCredentials, callCredentials);
  }

  @SuppressWarnings("unchecked")
  private static InternalProtocolNegotiator.ClientFactory createClientFactory() {
    SslContext sslContext;
    try {
      sslContext = GrpcSslContexts.forClient().build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
    Attributes.Key<String> clusterNameAttrKey = null;
    try {
      Class<?> klass = Class.forName("io.grpc.xds.InternalXdsAttributes");
      clusterNameAttrKey =
          (Attributes.Key<String>) klass.getField("ATTR_CLUSTER_NAME").get(null);
    } catch (ClassNotFoundException e) {
      logger.log(Level.FINE,
          "Unable to load xDS endpoint cluster name key, this may be expected", e);
    } catch (NoSuchFieldException e) {
      logger.log(Level.FINE,
          "Unable to load xDS endpoint cluster name key, this may be expected", e);
    } catch (IllegalAccessException e) {
      logger.log(Level.FINE,
          "Unable to load xDS endpoint cluster name key, this may be expected", e);
    }
    return new GoogleDefaultProtocolNegotiatorFactory(
        /* targetServiceAccounts= */ ImmutableList.<String>of(),
        SharedResourcePool.forResource(HandshakerServiceChannel.SHARED_HANDSHAKER_CHANNEL),
        sslContext,
        clusterNameAttrKey);
  }
}
