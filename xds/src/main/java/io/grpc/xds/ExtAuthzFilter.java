/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;
import io.grpc.xds.internal.ThreadSafeRandom;
import io.grpc.xds.internal.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.internal.extauthz.BufferingAuthzClientCall;
import io.grpc.xds.internal.extauthz.CheckRequestBuilder;
import io.grpc.xds.internal.extauthz.CheckResponseHandler;
import io.grpc.xds.internal.extauthz.ExtAuthzCertificateProvider;
import io.grpc.xds.internal.extauthz.ExtAuthzClientInterceptor;
import io.grpc.xds.internal.extauthz.ExtAuthzConfig;
import io.grpc.xds.internal.extauthz.ExtAuthzParseException;
import io.grpc.xds.internal.extauthz.ExtAuthzServerInterceptor;
import io.grpc.xds.internal.extauthz.StubManager;
import io.grpc.xds.internal.grpcservice.InsecureGrpcChannelFactory;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

final class ExtAuthzFilter implements Filter {

  private static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz";

  private static final String TYPE_URL_OVERRIDE_CONFIG =
      "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute";


  static final class ExtAuthzFilterConfig implements Filter.FilterConfig {

    private final ExtAuthzConfig extAuthzConfig;

    ExtAuthzFilterConfig(ExtAuthzConfig extAuthzConfig) {
      this.extAuthzConfig = extAuthzConfig;
    }

    public ExtAuthzConfig extAuthzConfig() {
      return extAuthzConfig;
    }

    @Override
    public String typeUrl() {
      return ExtAuthzFilter.TYPE_URL;
    }

    public static ExtAuthzFilterConfig fromProto(ExtAuthz extAuthzProto)
        throws ExtAuthzParseException {
      return new ExtAuthzFilterConfig(ExtAuthzConfig.fromProto(extAuthzProto));
    }
  }

  // Placeholder for the external authorization filter's override config.
  static final class ExtAuthzFilterConfigOverride implements Filter.FilterConfig {
    @Override
    public final String typeUrl() {
      return ExtAuthzFilter.TYPE_URL_OVERRIDE_CONFIG;
    }
  }

  static final class Provider implements Filter.Provider {

    @Override
    public String[] typeUrls() {
      return new String[] {TYPE_URL, TYPE_URL_OVERRIDE_CONFIG};
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public boolean isServerFilter() {
      return true;
    }

    @Override
    public ExtAuthzFilter newInstance(String name) {
      // Create a dedicated scheduler for this filter instance's StubManager
      StubManager stubManager = StubManager.create(InsecureGrpcChannelFactory.getInstance());
      return new ExtAuthzFilter(stubManager, ThreadSafeRandomImpl.INSTANCE,
              BufferingAuthzClientCall.FACTORY_INSTANCE, ExtAuthzCertificateProvider.create(),
          CheckRequestBuilder.INSTANCE, CheckResponseHandler.INSTANCE,
          ExtAuthzClientInterceptor.INSTANCE, ExtAuthzServerInterceptor.INSTANCE,
          HeaderMutationFilter.INSTANCE, HeaderMutator.create());
    }

    @Override
    public ConfigOrError<ExtAuthzFilterConfig> parseFilterConfig(Message rawProtoMessage) {
      ExtAuthz extAuthzProto;
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      Any anyMessage = (Any) rawProtoMessage;
      try {
        extAuthzProto = anyMessage.unpack(ExtAuthz.class);
        return ConfigOrError.fromConfig(ExtAuthzFilterConfig.fromProto(extAuthzProto));
      } catch (InvalidProtocolBufferException | ExtAuthzParseException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
    }

    @Override
    public ConfigOrError<ExtAuthzFilterConfigOverride> parseFilterConfigOverride(
        Message rawProtoMessage) {
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      return ConfigOrError.fromConfig(new ExtAuthzFilterConfigOverride());
    }
  }

  private final StubManager stubManager;
  private final ThreadSafeRandom random;
  private final BufferingAuthzClientCall.Factory bufferingAuthzClientCallFactory;
  private final ExtAuthzCertificateProvider certificateProvider;
  private final CheckRequestBuilder.Factory checkRequestBuilderFactory;
  private final CheckResponseHandler.Factory checkResponseHandlerFactory;
  private final ExtAuthzClientInterceptor.Factory extAuthzClientInterceptorFactory;
  private final ExtAuthzServerInterceptor.Factory extAuthzServerInterceptorFactory;
  private final HeaderMutationFilter.Factory headerMutationFilterFactory;
  private final HeaderMutator headerMutator;


  ExtAuthzFilter(StubManager stubManager, ThreadSafeRandom random,
      BufferingAuthzClientCall.Factory bufferingAuthzClientCallFactory,
      ExtAuthzCertificateProvider certificateProvider,
          CheckRequestBuilder.Factory checkRequestBuilderFactory,
      CheckResponseHandler.Factory checkResponseHandlerFactory,
      ExtAuthzClientInterceptor.Factory extAuthzClientInterceptorFactory,
      ExtAuthzServerInterceptor.Factory extAuthzServerInterceptorFactory,
          HeaderMutationFilter.Factory headerMutationFilterFactory, HeaderMutator headerMutator) {
    this.stubManager = stubManager;
    this.random = random;
    this.bufferingAuthzClientCallFactory = bufferingAuthzClientCallFactory;
    this.certificateProvider = certificateProvider;
    this.checkRequestBuilderFactory = checkRequestBuilderFactory;
    this.checkResponseHandlerFactory = checkResponseHandlerFactory;
    this.extAuthzClientInterceptorFactory = extAuthzClientInterceptorFactory;
    this.extAuthzServerInterceptorFactory = extAuthzServerInterceptorFactory;
    this.headerMutationFilterFactory = headerMutationFilterFactory;
    this.headerMutator = headerMutator;
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(FilterConfig config,
      @Nullable FilterConfig overrideConfig, ScheduledExecutorService scheduler) {
    if (overrideConfig != null) {
      return null;
    }
    if (!(config instanceof ExtAuthzFilterConfig)) {
      return null;
    }
    ExtAuthzFilterConfig extAuthzFilterConfig = (ExtAuthzFilterConfig) config;
    AuthorizationGrpc.AuthorizationStub stub =
        stubManager.getStub(extAuthzFilterConfig.extAuthzConfig());
    ExtAuthzConfig extAuthzConfig = extAuthzFilterConfig.extAuthzConfig();
    return extAuthzClientInterceptorFactory.create(extAuthzConfig, stub,
            random, bufferingAuthzClientCallFactory,
        checkRequestBuilderFactory.create(extAuthzConfig, certificateProvider),
        checkResponseHandlerFactory.create(headerMutator,
            headerMutationFilterFactory.create(extAuthzConfig.decoderHeaderMutationRules()),
            extAuthzConfig),
        headerMutator);
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(FilterConfig config,
      @Nullable FilterConfig overrideConfig) {
    if (overrideConfig != null) {
      return null;
    }
    if (!(config instanceof ExtAuthzFilterConfig)) {
      return null;
    }
    ExtAuthzFilterConfig extAuthzFilterConfig = (ExtAuthzFilterConfig) config;
    AuthorizationGrpc.AuthorizationStub stub =
        stubManager.getStub(extAuthzFilterConfig.extAuthzConfig());
    ExtAuthzConfig extAuthzConfig = extAuthzFilterConfig.extAuthzConfig();
    return extAuthzServerInterceptorFactory.create(extAuthzConfig, stub, random,
            checkRequestBuilderFactory.create(extAuthzConfig, certificateProvider),
        checkResponseHandlerFactory.create(headerMutator,
            headerMutationFilterFactory.create(extAuthzConfig.decoderHeaderMutationRules()),
            extAuthzConfig),
        headerMutator);
  }

  @Override
  public void close() {
    stubManager.close();
  }
}
