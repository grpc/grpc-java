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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.grpc.internal.GrpcUtil;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FaultFilter}. */
@RunWith(JUnit4.class)
public class FaultFilterTest {
  @Test
  public void parseConfig_withTypedStruct() {
    Map<String, Value> abortMap = ImmutableMap.of(
        "httpStatus", Value.newBuilder().setNumberValue(404D).build()
    );
    Struct abortStruct = Struct.newBuilder()
        .putAllFields(abortMap)
        .build();
    Map<String, Value> httpFaultMap = ImmutableMap.of(
        "abort", Value.newBuilder().setStructValue(abortStruct).build()
    );
    TypedStruct faultStruct = TypedStruct.newBuilder()
        .setTypeUrl(FaultFilter.TYPE_URL)
        .setValue(Struct.newBuilder().putAllFields(httpFaultMap).build())
        .build();
    Any rawConfig = Any.pack(faultStruct);
    FaultConfig faultConfig = FaultFilter.INSTANCE.parseFilterConfig(rawConfig).struct;
    assertThat(faultConfig.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
    FaultConfig faultConfigOverride =
        FaultFilter.INSTANCE.parseFilterConfigOverride(rawConfig).struct;
    assertThat(faultConfigOverride.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
  }

  @Test
  public void parseConfig_withoutTypedStruct() {
    Any rawConfig = Any.pack(
        HTTPFault.newBuilder().setAbort(FaultAbort.newBuilder().setHttpStatus(404)).build());
    FaultConfig faultConfig = FaultFilter.INSTANCE.parseFilterConfig(rawConfig).struct;
    assertThat(faultConfig.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
    FaultConfig faultConfigOverride =
        FaultFilter.INSTANCE.parseFilterConfigOverride(rawConfig).struct;
    assertThat(faultConfigOverride.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
  }
}
