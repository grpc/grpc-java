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

package io.grpc.internal;


import io.grpc.Attributes;

class OtelMetricsAttributes {
  final String target;
  final String backendService;
  final String locality;
  final String disconnectError;
  final String securityLevel;

  public OtelMetricsAttributes(String target, String backendService, String locality,
                               String disconnectError, String securityLevel) {
    this.target = target;
    this.backendService = backendService;
    this.locality = locality;
    this.disconnectError = disconnectError;
    this.securityLevel = securityLevel;
  }

  public Attributes toOtelMetricsAttributes() {
    Attributes attributes =
        Attributes.EMPTY;

    if (target != null) {
      attributes.toBuilder()
          .set(Attributes.Key.create("grpc.target"), target)
          .build();
    }
    if (backendService != null) {
      attributes.toBuilder()
          .set(Attributes.Key.create("grpc.lb.backend_service"), backendService)
          .build();
    }
    if (locality != null) {
      attributes.toBuilder()
          .set(Attributes.Key.create("grpc.lb.locality"), locality)
          .build();
    }
    if (disconnectError != null) {
      attributes.toBuilder()
          .set(Attributes.Key.create("grpc.disconnect_error"), disconnectError)
          .build();
    }
    if (securityLevel != null) {
      attributes.toBuilder()
          .set(Attributes.Key.create("grpc.security_level"), securityLevel)
          .build();
    }
    return attributes;
  }
}