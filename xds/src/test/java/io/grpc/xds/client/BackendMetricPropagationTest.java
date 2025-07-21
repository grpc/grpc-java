/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.client;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link BackendMetricPropagation}.
 */
@RunWith(JUnit4.class)
public class BackendMetricPropagationTest {

  @Test
  public void fromMetricSpecs_nullInput() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(null);
    assertThat(config.propagateCpuUtilization).isFalse();
    assertThat(config.propagateMemUtilization).isFalse();
    assertThat(config.propagateApplicationUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("any")).isFalse();
  }

  @Test
  public void fromMetricSpecs_emptyInput() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(ImmutableList.of());
    assertThat(config.propagateCpuUtilization).isFalse();
    assertThat(config.propagateMemUtilization).isFalse();
    assertThat(config.propagateApplicationUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("any")).isFalse();
  }

  @Test
  public void fromMetricSpecs_partialStandardMetrics() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("cpu_utilization", "mem_utilization"));
    assertThat(config.propagateCpuUtilization).isTrue();
    assertThat(config.propagateMemUtilization).isTrue();
    assertThat(config.propagateApplicationUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("any")).isFalse();
  }

  @Test
  public void fromMetricSpecs_allStandardMetrics() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("cpu_utilization", "mem_utilization", "application_utilization"));
    assertThat(config.propagateCpuUtilization).isTrue();
    assertThat(config.propagateMemUtilization).isTrue();
    assertThat(config.propagateApplicationUtilization).isTrue();
    assertThat(config.shouldPropagateNamedMetric("any")).isFalse();
  }

  @Test
  public void fromMetricSpecs_wildcardNamedMetrics() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("named_metrics.*"));
    assertThat(config.propagateCpuUtilization).isFalse();
    assertThat(config.propagateMemUtilization).isFalse();
    assertThat(config.propagateApplicationUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("any_key")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("another_key")).isTrue();
  }

  @Test
  public void fromMetricSpecs_specificNamedMetrics() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("named_metrics.foo", "named_metrics.bar"));
    assertThat(config.shouldPropagateNamedMetric("foo")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("bar")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("baz")).isFalse();
    assertThat(config.shouldPropagateNamedMetric("any")).isFalse();
  }

  @Test
  public void fromMetricSpecs_mixedStandardAndNamed() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("cpu_utilization", "named_metrics.foo", "named_metrics.bar"));
    assertThat(config.propagateCpuUtilization).isTrue();
    assertThat(config.propagateMemUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("foo")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("bar")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("baz")).isFalse();
  }

  @Test
  public void fromMetricSpecs_wildcardAndSpecificNamedMetrics() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of("named_metrics.foo", "named_metrics.*"));
    assertThat(config.shouldPropagateNamedMetric("foo")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("bar")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("any_other_key")).isTrue();
  }

  @Test
  public void fromMetricSpecs_malformedAndUnknownSpecs_areIgnored() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        asList(
            "cpu_utilization",      // valid
            null,                   // ignored
            "disk_utilization",     // ignored
            "named_metrics.",       // ignored, empty key
            "named_metrics.valid"   // valid
        ));

    assertThat(config.propagateCpuUtilization).isTrue();
    assertThat(config.propagateMemUtilization).isFalse();
    assertThat(config.shouldPropagateNamedMetric("disk_utilization")).isFalse();
    assertThat(config.shouldPropagateNamedMetric("valid")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("")).isFalse(); // from the empty key
  }

  @Test
  public void fromMetricSpecs_duplicateSpecs_areHandledGracefully() {
    BackendMetricPropagation config = BackendMetricPropagation.fromMetricSpecs(
        ImmutableList.of(
            "cpu_utilization",
            "named_metrics.foo",
            "cpu_utilization",
            "named_metrics.foo"));
    assertThat(config.propagateCpuUtilization).isTrue();
    assertThat(config.shouldPropagateNamedMetric("foo")).isTrue();
    assertThat(config.shouldPropagateNamedMetric("bar")).isFalse();
  }
}
