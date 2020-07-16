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

package io.grpc.xds.internal.engine;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for constructor of CEL Evaluation Engine. */
@RunWith(JUnit4.class)
public class CelEngineTest {
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private CelEvaluationEngine<?,?> engine;
  private RBAC rbacDeny;
  private RBAC rbacAllow;

  @Before
  public void setup() {
    rbacAllow = RBAC.newBuilder()
        .setAction(Action.ALLOW)
        .build();
    rbacDeny = RBAC.newBuilder()
        .setAction(Action.DENY)
        .build();
  }

  @Test
  public void createEngineSingleRbac() {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNotNull(engine);
  }

  @Test
  public void createEngineRbacPairOfDenyAllow() {
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNotNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowAllow() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfAllowDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow, rbacDeny}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfRbacPairOfDenyDeny() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage( "Invalid RBAC list, " 
        + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacDeny, rbacDeny}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfEmptyRbacList() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    List<RBAC> rbacList = new ArrayList<>();
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNull(engine);
  }

  @Test
  public void failToCreateEngineIfTooLongRbacList() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(
        new RBAC[] {rbacAllow, rbacAllow, rbacAllow}));
    engine = new CelEvaluationEngine<>(ImmutableList.copyOf(rbacList));
    assertNull(engine);
  }
}
