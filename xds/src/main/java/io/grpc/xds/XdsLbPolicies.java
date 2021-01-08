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

package io.grpc.xds;

final class XdsLbPolicies {
  static final String CLUSTER_MANAGER_POLICY_NAME = "cluster_manager_experimental";
  static final String CDS_POLICY_NAME = "cds_experimental";
  static final String EDS_POLICY_NAME = "eds_experimental";
  static final String CLUSTER_RESOLVER_POLICY_NAME = "cluster_resolver_experimental";
  static final String PRIORITY_POLICY_NAME = "priority_experimental";
  static final String CLUSTER_IMPL_POLICY_NAME = "cluster_impl_experimental";
  static final String WEIGHTED_TARGET_POLICY_NAME = "weighted_target_experimental";
  static final String LRS_POLICY_NAME = "lrs_experimental";

  private XdsLbPolicies() {}
}
