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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** The collection of global interceptors and global server stream tracers. */
@Internal
public final class GlobalInterceptors {
  private static GlobalInterceptors instance = null;
  private List<ClientInterceptor> clientInterceptors;
  private List<ServerInterceptor> serverInterceptors;
  private List<ServerStreamTracer.Factory> serverStreamTracerFactories;
  private boolean isGlobalInterceptorsTracersSet;
  private boolean isGlobalInterceptorsTracersGet;

  @VisibleForTesting
  GlobalInterceptors() {}

  /** Returns the GlobalInterceptors instance. */
  private static GlobalInterceptors getInstance() {
    if (instance == null) {
      instance = new GlobalInterceptors();
    }
    return instance;
  }

  /**
   * Sets the list of global interceptors and global server stream tracers.
   *
   * <p>If {@code setInterceptorsTracers()} is called again, this method will throw {@link
   * IllegalStateException}.
   *
   * <p>It is only safe to call early. This method throws {@link IllegalStateException} after any of
   * the get calls [{@link #getClientInterceptors()}, {@link #getServerInterceptors()} or {@link
   * #getServerStreamTracerFactories()}] has been called, in order to limit changes to the result of
   * {@code setInterceptorsTracers()}.
   *
   * @param clientInterceptorList list of {@link ClientInterceptor} that make up global Client
   *     Interceptors.
   * @param serverInterceptorList list of {@link ServerInterceptor} that make up global Server
   *     Interceptors.
   * @param serverStreamTracerFactoryList list of {@link ServerStreamTracer.Factory} that make up
   *     global ServerStreamTracer factories.
   */
  public static synchronized void setInterceptorsTracers(
      List<ClientInterceptor> clientInterceptorList,
      List<ServerInterceptor> serverInterceptorList,
      List<ServerStreamTracer.Factory> serverStreamTracerFactoryList) {
    try {
      GlobalInterceptors.getInstance()
          .setGlobalInterceptorsTracers(
              clientInterceptorList, serverInterceptorList, serverStreamTracerFactoryList);
    } catch (Exception exception) {
      throw exception;
    }
  }

  /** Returns the list of global {@link ClientInterceptor}. If not set, this returns null. */
  public static synchronized List<ClientInterceptor> getClientInterceptors() {
    return GlobalInterceptors.getInstance().getGlobalClientInterceptors();
  }

  /** Returns list of global {@link ServerInterceptor}. If not set, this returns null. */
  public static synchronized List<ServerInterceptor> getServerInterceptors() {
    return GlobalInterceptors.getInstance().getGlobalServerInterceptors();
  }

  /** Returns list of global {@link ServerStreamTracer.Factory}. If not set, this returns null. */
  public static synchronized List<ServerStreamTracer.Factory> getServerStreamTracerFactories() {
    return GlobalInterceptors.getInstance().getGlobalServerStreamTracerFactories();
  }

  @VisibleForTesting
  void setGlobalInterceptorsTracers(
      List<ClientInterceptor> clientInterceptorList,
      List<ServerInterceptor> serverInterceptorList,
      List<ServerStreamTracer.Factory> serverStreamTracerFactoryList) {
    if (isGlobalInterceptorsTracersGet) {
      throw new IllegalStateException("Set cannot be called after any get call");
    }
    if (isGlobalInterceptorsTracersSet) {
      throw new IllegalStateException("Global interceptors and tracers are already set");
    }

    if (clientInterceptorList != null) {
      clientInterceptors =
          ImmutableList.<ClientInterceptor>builder().addAll(clientInterceptorList).build();
    }

    if (serverInterceptorList != null) {
      serverInterceptors =
          ImmutableList.<ServerInterceptor>builder().addAll(serverInterceptorList).build();
    }

    if (serverStreamTracerFactoryList != null) {
      serverStreamTracerFactories =
          ImmutableList.<ServerStreamTracer.Factory>builder()
              .addAll(serverStreamTracerFactoryList)
              .build();
    }
    isGlobalInterceptorsTracersSet = true;
  }

  @VisibleForTesting
  List<ClientInterceptor> getGlobalClientInterceptors() {
    isGlobalInterceptorsTracersGet = true;
    return clientInterceptors;
  }

  @VisibleForTesting
  List<ServerInterceptor> getGlobalServerInterceptors() {
    isGlobalInterceptorsTracersGet = true;
    return serverInterceptors;
  }

  @VisibleForTesting
  List<ServerStreamTracer.Factory> getGlobalServerStreamTracerFactories() {
    isGlobalInterceptorsTracersGet = true;
    return serverStreamTracerFactories;
  }
}
