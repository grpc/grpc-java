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

package io.grpc.xds.internal;

import static com.google.common.truth.Truth.assertThat;

import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Cel Evaluation Engine. */
@RunWith(JUnit4.class)
public class CelEvaluationEngineTest<ReqT, RespT> {
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private CelEvaluationEngine<ReqT,RespT> engine;

  @Test
  public void createEngineSingleRbac() {
    RBAC rbac = RBAC.newBuilder().build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbac}));
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNotNull();
  }

  @Test
  public void createEngineRbacPairOfDenyAllow() {
    RBAC rbac1 = RBAC.newBuilder()
        .setAction(RBAC.Action.DENY)
        .build();
    RBAC rbac2 = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbac1, rbac2}));
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNotNull();
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowAllow() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    RBAC rbac1 = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    RBAC rbac2 = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbac1, rbac2}));
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNull();
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    RBAC rbac1 = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    RBAC rbac2 = RBAC.newBuilder()
        .setAction(RBAC.Action.DENY)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbac1, rbac2}));
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNull();
  }

  @Test
  public void failToCreateEngineIfRbacPairOfDenyDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    RBAC rbac1 = RBAC.newBuilder()
        .setAction(RBAC.Action.DENY)
        .build();
    RBAC rbac2 = RBAC.newBuilder()
        .setAction(RBAC.Action.DENY)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbac1, rbac2}));
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNull();
  }

  @Test
  public void failToCreateEngineIfEmptyRbacList() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    List<RBAC> rbacList = new ArrayList<>();
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNull();
  }

  @Test
  public void failToCreateEngineIfTooLongRbacList() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    List<RBAC> rbacList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      RBAC rbac = RBAC.newBuilder().build();
      rbacList.add(rbac);
    }
    engine = new CelEvaluationEngine<>(rbacList);
    assertThat(engine).isNull();
  }
}
