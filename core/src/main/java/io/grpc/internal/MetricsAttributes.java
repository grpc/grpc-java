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

class MetricsAttributes {
  final String target;
  final String backendService;
  final String locality;
  final String disconnectError;
  final String securityLevel;

  // Constructor is private, only the Builder can call it
  private MetricsAttributes(Builder builder) {
    this.target = builder.target;
    this.backendService = builder.backendService;
    this.locality = builder.locality;
    this.disconnectError = builder.disconnectError;
    this.securityLevel = builder.securityLevel;
  }

  // Public static method to get a new builder instance
  public static Builder newBuilder(String target) {
    return new Builder(target);
  }

  public static class Builder {
    // Required parameter
    private final String target;

    // Optional parameters - initialized to default values
    private String backendService = null;
    private String locality = null;
    private String disconnectError = null;
    private String securityLevel = null;

    public Builder(String target) {
      this.target = target;
    }

    public Builder backendService(String val) {
      this.backendService = val;
      return this;
    }

    public Builder locality(String val) {
      this.locality = val;
      return this;
    }

    public Builder disconnectError(String val) {
      this.disconnectError = val;
      return this;
    }

    public Builder securityLevel(String val) {
      this.securityLevel = val;
      return this;
    }

    public MetricsAttributes build() {
      return new MetricsAttributes(this);
    }
  }
}