/*
 * Copyright 2017, Google Inc. All rights reserved.
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
 * A client to gRPC Testing API.
 *
 * The interfaces provided are listed below, along with usage samples.
 *
 * =================
 * TestServiceClient
 * =================
 *
 * Service Description: A simple service to test the various types of RPCs and experiment with
 * performance with various types of payload.
 *
 * Sample for TestServiceClient:
 * <pre>
 * <code>
 * try (TestServiceClient testServiceClient = TestServiceClient.create()) {
 *   Empty request = Empty.newBuilder().build();
 *   Empty response = testServiceClient.emptyCall(request);
 * }
 * </code>
 * </pre>
 *
 */

package io.grpc.testing.integration.gapic;