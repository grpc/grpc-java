/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.alts;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;

/**
 * Internal constants used with {@link GoogleDefaultChannelCredentials}. This is intended for
 * usage internal to the gRPC team. If you *really* think you need to use this, contact the
 * gRPC team first.
 */
@Internal
public class InternalGoogleDefaultConstants {

  /**
   * Name of the cluster (in xDS concept) that provides this EquivalentAddressGroup.
   */
  @EquivalentAddressGroup.Attr
  public static final Attributes.Key<String> ATTR_XDS_CLUSTER_NAME =
      Attributes.Key.create("io.grpc.alts.xdsClusterName");

  /**
   * Cluster name (in xDS concept) representing a Google CFE.
   */
  public static final String GOOGLE_CFE_CLUSTER_NAME = "google_cfe";

  private InternalGoogleDefaultConstants() {}
}
