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

package io.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * The collection of default interceptors and stream tracers.
 */
@Internal
public final class GlobalInterceptors {
  private static volatile GlobalInterceptors instance = null;

  /** Returns the default GlobalInterceptors instance. */
  public static synchronized GlobalInterceptors getDefaultInterceptors() {
    if (instance == null) {
      synchronized (GlobalInterceptors.class) {
        if (instance == null) {
          instance = new GlobalInterceptors();
        }
      }
    }
    return instance;
  }

  /** Sets the list of global {@link ClientInterceptor}.
   *
   * @throws IllegalStateException if setClientInterceptors is called twice
   */
  public static void setClientInterceptors(List<ClientInterceptor> clientInterceptorList) {
    try {
      GlobalInterceptors.getDefaultInterceptors()
          .setGlobalClientInterceptors(clientInterceptorList);
    } catch (Exception e) {
      throw e;
    }
  }

  /** Sets the list of global {@link ServerInterceptor}.
   *
   * @throws IllegalStateException if setServerInterceptors is called twice
   */
  public static void setServerInterceptors(List<ServerInterceptor> serverInterceptorList) {
    try {
      GlobalInterceptors.getDefaultInterceptors()
          .setGlobalServerInterceptors(serverInterceptorList);
    } catch (Exception e) {
      throw e;
    }
  }

  /** Sets the list of global {@link ClientStreamTracer.Factory}.
   *
   * @throws IllegalStateException if setClientStreamTracerFactories is called twice
   */
  public static void setClientStreamTracerFactories(
      List<ClientStreamTracer.Factory> clientStreamTracerFactoriesList) {
    try {
      GlobalInterceptors.getDefaultInterceptors()
          .setGlobalClientStreamTracerFactories(clientStreamTracerFactoriesList);
    } catch (Exception e) {
      throw e;
    }
  }

  /** Sets the list of global {@link ServerStreamTracer.Factory}.
   *
   * @throws IllegalStateException if setServerStreamTracerFactories is called twice
   */
  public static void setServerStreamTracerFactories(
      List<ServerStreamTracer.Factory> serverStreamTracerFactoriesList) {
    try {
      GlobalInterceptors.getDefaultInterceptors()
          .setGlobalServerStreamTracerFactories(serverStreamTracerFactoriesList);
    } catch (Exception e) {
      throw e;
    }
  }

  /** Gets the list of global {@link ClientInterceptor}. If not set, returns an empty list. */
  public static List<ClientInterceptor> getClientInterceptors() {
    return GlobalInterceptors.getDefaultInterceptors().getGlobalClientInterceptors();
  }

  /** Gets list of global {@link ServerInterceptor}. If not set, returns an empty list. */
  public static List<ServerInterceptor> getServerInterceptors() {
    return GlobalInterceptors.getDefaultInterceptors().getGlobalServerInterceptors();
  }

  /** Gets list og global {@link ClientStreamTracer.Factory}. If not set, returns an empty list. */
  public static List<ClientStreamTracer.Factory> getClientStreamTracerFactories() {
    return GlobalInterceptors.getDefaultInterceptors().getGlobalClientStreamTracerFactories();
  }

  /** Gets list of global {@link ServerStreamTracer.Factory}. If not set, returns an empty list. */
  public static List<ServerStreamTracer.Factory> getServerStreamTracerFactories() {
    return GlobalInterceptors.getDefaultInterceptors().getGlobalServerStreamTracerFactories();
  }

  private List<ClientInterceptor> clientInterceptors = new ArrayList<>();
  private List<ServerInterceptor> serverInterceptors = new ArrayList<>();
  private List<ClientStreamTracer.Factory> clientStreamTracerFactories = new ArrayList<>();
  private List<ServerStreamTracer.Factory> serverStreamTracerFactories = new ArrayList<>();
  private boolean clientInterceptorsSet;
  private boolean serverInterceptorsSet;
  private boolean clientStreamTracersSet;
  private boolean serverStreamTracersSet;
  private boolean clientInterceptorsGet;
  private boolean serverInterceptorsGet;
  private boolean clientStreamTracersGet;
  private boolean serverStreamTracersGet;

  @VisibleForTesting
  GlobalInterceptors() {}

  @VisibleForTesting
  synchronized void setGlobalClientInterceptors(List<ClientInterceptor> clientInterceptorList) {
    checkNotNull(clientInterceptorList, "clientInterceptorList");
    if (clientInterceptorsGet) {
      throw new IllegalStateException("Set cannot be called after corresponding Get call");
    }
    if (clientInterceptorsSet) {
      throw new IllegalStateException("Client interceptors are already set");
    }
    ImmutableList.Builder<ClientInterceptor> interceptorBuilder = new ImmutableList.Builder<>();
    for (ClientInterceptor interceptor : clientInterceptorList) {
      interceptorBuilder.add(interceptor);
    }
    clientInterceptors = interceptorBuilder.build();
    clientInterceptorsSet = true;
  }

  @VisibleForTesting
  synchronized void setGlobalServerInterceptors(List<ServerInterceptor> serverInterceptorList) {
    checkNotNull(serverInterceptorList, "serverInterceptorList");
    if (serverInterceptorsGet) {
      throw new IllegalStateException("Set cannot be called after corresponding Get call");
    }
    if (serverInterceptorsSet) {
      throw new IllegalStateException("Server interceptors are already set");
    }
    ImmutableList.Builder<ServerInterceptor> interceptorBuilder = new ImmutableList.Builder<>();
    for (ServerInterceptor interceptor : serverInterceptorList) {
      interceptorBuilder.add(interceptor);
    }
    serverInterceptors = interceptorBuilder.build();
    serverInterceptorsSet = true;
  }

  @VisibleForTesting
  synchronized void setGlobalClientStreamTracerFactories(
      List<ClientStreamTracer.Factory> clientStreamTracerFactoriesList) {
    checkNotNull(clientStreamTracerFactoriesList, "clientStreamTracerFactoriesList");
    if (clientStreamTracersGet) {
      throw new IllegalStateException("Set cannot be called after corresponding Get call");
    }
    if (clientStreamTracersSet) {
      throw new IllegalStateException("ClientStreamTracer factories are already set");
    }
    ImmutableList.Builder<ClientStreamTracer.Factory> factoryBuilder =
        new ImmutableList.Builder<>();
    for (ClientStreamTracer.Factory factory : clientStreamTracerFactoriesList) {
      factoryBuilder.add(factory);
    }
    clientStreamTracerFactories = factoryBuilder.build();
    clientStreamTracersSet = true;
  }

  @VisibleForTesting
  synchronized void setGlobalServerStreamTracerFactories(
      List<ServerStreamTracer.Factory> serverStreamTracerFactoriesList) {
    checkNotNull(serverStreamTracerFactoriesList, "serverStreamTracerFactoriesList");
    if (serverStreamTracersGet) {
      throw new IllegalStateException("Set cannot be called after corresponding Get call");
    }
    if (serverStreamTracersSet) {
      throw new IllegalStateException("ServerStreamTracer factories are already set");
    }
    ImmutableList.Builder<ServerStreamTracer.Factory> factoryBuilder =
        new ImmutableList.Builder<>();
    for (ServerStreamTracer.Factory factory : serverStreamTracerFactoriesList) {
      factoryBuilder.add(factory);
    }
    serverStreamTracerFactories = factoryBuilder.build();
    serverStreamTracersSet = true;
  }

  @VisibleForTesting
  synchronized List<ClientInterceptor> getGlobalClientInterceptors() {
    if (!clientInterceptorsGet) {
      clientInterceptorsGet = true;
    }
    return clientInterceptors;
  }

  @VisibleForTesting
  synchronized List<ServerInterceptor> getGlobalServerInterceptors() {
    if (!serverInterceptorsGet) {
      serverInterceptorsGet = true;
    }
    return serverInterceptors;
  }

  @VisibleForTesting
  synchronized List<ClientStreamTracer.Factory> getGlobalClientStreamTracerFactories() {
    if (!clientStreamTracersGet) {
      clientStreamTracersGet = true;
    }
    return clientStreamTracerFactories;
  }

  @VisibleForTesting
  synchronized List<ServerStreamTracer.Factory> getGlobalServerStreamTracerFactories() {
    if (!serverStreamTracersGet) {
      serverStreamTracersGet = true;
    }
    return serverStreamTracerFactories;
  }
}
