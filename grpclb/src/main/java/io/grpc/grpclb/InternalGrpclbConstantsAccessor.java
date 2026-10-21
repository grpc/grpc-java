/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.grpclb;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import java.util.List;

/**
 * Internal {@link GrpclbConstants} accessor. This is intended for usage internal to the gRPC
 * team. If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalGrpclbConstantsAccessor {

  // Prevent instantiation.
  private InternalGrpclbConstantsAccessor() {
  }

  public static final Attributes.Key<List<EquivalentAddressGroup>> ATTR_LB_ADDRS =
      GrpclbConstants.ATTR_LB_ADDRS;

  @EquivalentAddressGroup.Attr
  public static final Attributes.Key<String> ATTR_LB_ADDR_AUTHORITY =
      GrpclbConstants.ATTR_LB_ADDR_AUTHORITY;
}
