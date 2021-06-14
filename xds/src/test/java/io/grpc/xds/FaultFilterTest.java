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

import com.google.protobuf.Any;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.HeaderAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.Status.Code;
import io.grpc.internal.GrpcUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FaultFilter}. */
@RunWith(JUnit4.class)
public class FaultFilterTest {

  @Test
  public void parseFaultAbort_convertHttpStatus() {
    Any rawConfig = Any.pack(
        HTTPFault.newBuilder().setAbort(FaultAbort.newBuilder().setHttpStatus(404)).build());
    FaultConfig faultConfig = FaultFilter.INSTANCE.parseFilterConfig(rawConfig).config;
    assertThat(faultConfig.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
    FaultConfig faultConfigOverride =
        FaultFilter.INSTANCE.parseFilterConfigOverride(rawConfig).config;
    assertThat(faultConfigOverride.faultAbort().status().getCode())
        .isEqualTo(GrpcUtil.httpStatusToGrpcStatus(404).getCode());
  }

  @Test
  public void parseFaultAbort_withHeaderAbort() {
    io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort proto =
        io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.newBuilder()
            .setPercentage(FractionalPercent.newBuilder()
                .setNumerator(20).setDenominator(DenominatorType.HUNDRED))
            .setHeaderAbort(HeaderAbort.getDefaultInstance()).build();
    FaultConfig.FaultAbort faultAbort = FaultFilter.parseFaultAbort(proto).config;
    assertThat(faultAbort.headerAbort()).isTrue();
    assertThat(faultAbort.percent().numerator()).isEqualTo(20);
    assertThat(faultAbort.percent().denominatorType())
        .isEqualTo(FaultConfig.FractionalPercent.DenominatorType.HUNDRED);
  }

  @Test
  public void parseFaultAbort_withHttpStatus() {
    io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort proto =
        io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.newBuilder()
            .setPercentage(FractionalPercent.newBuilder()
                .setNumerator(100).setDenominator(DenominatorType.TEN_THOUSAND))
            .setHttpStatus(400).build();
    FaultConfig.FaultAbort res = FaultFilter.parseFaultAbort(proto).config;
    assertThat(res.percent().numerator()).isEqualTo(100);
    assertThat(res.percent().denominatorType())
        .isEqualTo(FaultConfig.FractionalPercent.DenominatorType.TEN_THOUSAND);
    assertThat(res.status().getCode()).isEqualTo(Code.INTERNAL);
  }

  @Test
  public void parseFaultAbort_withGrpcStatus() {
    io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort proto =
        io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.newBuilder()
            .setPercentage(FractionalPercent.newBuilder()
                .setNumerator(600).setDenominator(DenominatorType.MILLION))
            .setGrpcStatus(Code.DEADLINE_EXCEEDED.value()).build();
    FaultConfig.FaultAbort faultAbort = FaultFilter.parseFaultAbort(proto).config;
    assertThat(faultAbort.percent().numerator()).isEqualTo(600);
    assertThat(faultAbort.percent().denominatorType())
        .isEqualTo(FaultConfig.FractionalPercent.DenominatorType.MILLION);
    assertThat(faultAbort.status().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
  }
}
