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

import static com.google.common.truth.Truth.assertThat;

import io.grpc.NameResolver.ConfigOrError;
import io.grpc.grpclb.GrpclbState.Mode;
import io.grpc.internal.JsonParser;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpclbLoadBalancerProviderTest {
  private final GrpclbLoadBalancerProvider provider = new GrpclbLoadBalancerProvider();

  @Test
  public void retrieveModeFromLbConfig_pickFirst() throws Exception {
    String lbConfig = "{\"childPolicy\" : [{\"pick_first\" : {}}, {\"round_robin\" : {}}]}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.PICK_FIRST);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_roundRobin() throws Exception {
    String lbConfig = "{\"childPolicy\" : [{\"round_robin\" : {}}, {\"pick_first\" : {}}]}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.ROUND_ROBIN);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_nullConfigUseRoundRobin() throws Exception {
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(null);

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.ROUND_ROBIN);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_emptyConfigUseRoundRobin() throws Exception {
    String lbConfig = "{}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.ROUND_ROBIN);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_emptyChildPolicyUseRoundRobin() throws Exception {
    String lbConfig = "{\"childPolicy\" : []}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.ROUND_ROBIN);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_unsupportedChildPolicy()
      throws Exception {
    String lbConfig = "{\"childPolicy\" : [ {\"nonono\" : {}} ]}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getDescription())
        .contains("None of [nonono] specified child policies are available.");
  }

  @Test
  public void retrieveModeFromLbConfig_skipUnsupportedChildPolicy() throws Exception {
    String lbConfig = "{\"childPolicy\" : [ {\"nono\" : {}}, {\"pick_first\" : {} } ]}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.PICK_FIRST);
    assertThat(config.getServiceName()).isNull();
  }

  @Test
  public void retrieveModeFromLbConfig_skipUnsupportedChildPolicyWithTarget() throws Exception {
    String lbConfig = "{\"childPolicy\" : [ {\"nono\" : {}}, {\"pick_first\" : {}} ],"
        + "\"serviceName\": \"foo.google.com\"}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getConfig()).isNotNull();
    GrpclbConfig config = (GrpclbConfig) configOrError.getConfig();
    assertThat(config.getMode()).isEqualTo(Mode.PICK_FIRST);
    assertThat(config.getServiceName()).isEqualTo("foo.google.com");
  }

  @Test
  public void retrieveModeFromLbConfig_wrongChildPolicyType() throws Exception {
    String lbConfig = "{\"childPolicy\" : {}}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCause()).hasMessageThat().contains("is not List");
  }

  @Test
  public void retrieveModeFromLbConfig_wrongChildPolicyTypeWithTarget() throws Exception {
    String lbConfig = "{\"childPolicy\" : {}, \"serviceName\": \"foo.google.com\"}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));

    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCause()).hasMessageThat().contains("is not List");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws Exception {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
