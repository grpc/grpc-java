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

/**
 * Library for gPRC proxyless service mesh using Envoy xDS protocol.
 *
 * <p>The package currently includes a name resolver plugin and a family of load balancer plugins.
 * A gRPC channel for a target with {@code "xds-experimental"} scheme will load the plugins and a
 * bootstrap file, and will communicate with an external control plane management server (e.g.
 * Traffic Director) that speaks Envoy xDS protocol to retrieve routing, load balancing, load
 * reporting configurations etc. for the channel. More features will be added.
 *
 * <p>The library is currently in an agile development phase, so API and design are subject to
 * breaking changes.
 */
@io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/5288")
package io.grpc.xds;
