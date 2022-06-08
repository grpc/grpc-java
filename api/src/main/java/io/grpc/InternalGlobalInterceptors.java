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

import java.util.List;

/** Accessor to internal methods of {@link GlobalInterceptors}. */
@Internal
public final class InternalGlobalInterceptors {

  public static void setInterceptorsTracers(
      List<ClientInterceptor> clientInterceptorList,
      List<ServerInterceptor> serverInterceptorList,
      List<ServerStreamTracer.Factory> serverStreamTracerFactoryList) {
    GlobalInterceptors.setInterceptorsTracers(
        clientInterceptorList, serverInterceptorList, serverStreamTracerFactoryList);
  }

  public static List<ClientInterceptor> getClientInterceptors() {
    return GlobalInterceptors.getClientInterceptors();
  }

  public static List<ServerInterceptor> getServerInterceptors() {
    return GlobalInterceptors.getServerInterceptors();
  }

  public static List<ServerStreamTracer.Factory> getServerStreamTracerFactories() {
    return GlobalInterceptors.getServerStreamTracerFactories();
  }

  private InternalGlobalInterceptors() {}
}
