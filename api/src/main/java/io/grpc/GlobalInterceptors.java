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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The collection of global interceptors and global server stream tracers. */
@Internal
final class GlobalInterceptors {
  private static List<ClientInterceptor> clientInterceptors = Collections.emptyList();
  private static List<ServerInterceptor> serverInterceptors = Collections.emptyList();
  private static List<ServerStreamTracer.Factory> serverStreamTracerFactories =
      Collections.emptyList();
  private static boolean isGlobalInterceptorsTracersSet;
  private static boolean isGlobalInterceptorsTracersGet;

  // Prevent instantiation
  private GlobalInterceptors() {}

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
  static synchronized void setInterceptorsTracers(
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
      clientInterceptors = Collections.unmodifiableList(new ArrayList<>(clientInterceptorList));
    }

    if (serverInterceptorList != null) {
      serverInterceptors = Collections.unmodifiableList(new ArrayList<>(serverInterceptorList));
    }

    if (serverStreamTracerFactoryList != null) {
      serverStreamTracerFactories =
          Collections.unmodifiableList(new ArrayList<>(serverStreamTracerFactoryList));
    }
    isGlobalInterceptorsTracersSet = true;
  }

  /**
   * Returns the list of global {@link ClientInterceptor}. If not set, this returns am empty list.
   */
  static synchronized List<ClientInterceptor> getClientInterceptors() {
    isGlobalInterceptorsTracersGet = true;
    return clientInterceptors;
  }

  /** Returns list of global {@link ServerInterceptor}. If not set, this returns an empty list. */
  static synchronized List<ServerInterceptor> getServerInterceptors() {
    isGlobalInterceptorsTracersGet = true;
    return serverInterceptors;
  }

  /**
   * Returns list of global {@link ServerStreamTracer.Factory}. If not set, this returns an empty
   * list.
   */
  static synchronized List<ServerStreamTracer.Factory> getServerStreamTracerFactories() {
    isGlobalInterceptorsTracersGet = true;
    return serverStreamTracerFactories;
  }
}
