/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.gcp.observability.ObservabilityConfig;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ConfigFilterHelperTest {
  private static final ImmutableList<LogFilter> configLogFilters =
      ImmutableList.of(
          new LogFilter(Collections.emptySet(), Collections.singleton("service1/Method2"), false,
              1024, 1024, false),
          new LogFilter(
              Collections.singleton("service2"), Collections.singleton("service4/method2"), false,
              2048, 1024, false),
          new LogFilter(
              Collections.singleton("service2"), Collections.singleton("service4/method3"), false,
              2048, 1024, true),
          new LogFilter(
              Collections.emptySet(), Collections.emptySet(), true,
              128, 128, false));

  private ObservabilityConfig mockConfig;
  private ConfigFilterHelper configFilterHelper;

  @Before
  public void setup() {
    mockConfig = mock(ObservabilityConfig.class);
    configFilterHelper = ConfigFilterHelper.getInstance(mockConfig);
  }

  @Test
  public void enableCloudLogging_withoutLogFilters() {
    when(mockConfig.isEnableCloudLogging()).thenReturn(true);
    assertThat(mockConfig.getClientLogFilters()).isEmpty();
    assertThat(mockConfig.getServerLogFilters()).isEmpty();
  }

  @Test
  public void checkMethodLog_withoutLogFilters() {
    when(mockConfig.isEnableCloudLogging()).thenReturn(true);
    assertThat(mockConfig.getClientLogFilters()).isEmpty();
    assertThat(mockConfig.getServerLogFilters()).isEmpty();

    FilterParams expectedParams =
        FilterParams.create(false, 0, 0);
    FilterParams clientResultParams
        = configFilterHelper.logRpcMethod("service3/Method3", true);
    assertThat(clientResultParams).isEqualTo(expectedParams);
    FilterParams serverResultParams
        = configFilterHelper.logRpcMethod("service3/Method3", false);
    assertThat(serverResultParams).isEqualTo(expectedParams);
  }

  @Test
  public void checkMethodAlwaysLogged() {
    List<LogFilter> sampleLogFilters =
        ImmutableList.of(
            new LogFilter(
                Collections.emptySet(), Collections.emptySet(), true,
                4096, 4096, false));
    when(mockConfig.getClientLogFilters()).thenReturn(sampleLogFilters);
    when(mockConfig.getServerLogFilters()).thenReturn(sampleLogFilters);

    FilterParams expectedParams =
        FilterParams.create(true, 4096, 4096);
    FilterParams clientResultParams
        = configFilterHelper.logRpcMethod("service1/Method6", true);
    assertThat(clientResultParams).isEqualTo(expectedParams);
    FilterParams serverResultParams
        = configFilterHelper.logRpcMethod("service1/Method6", false);
    assertThat(serverResultParams).isEqualTo(expectedParams);
  }

  @Test
  public void checkMethodNotToBeLogged() {
    List<LogFilter> sampleLogFilters =
        ImmutableList.of(
            new LogFilter(Collections.emptySet(), Collections.singleton("service2/*"), false,
                1024, 1024, true),
            new LogFilter(
                Collections.singleton("service2/Method1"), Collections.emptySet(), false,
                2048, 1024, false));
    when(mockConfig.getClientLogFilters()).thenReturn(sampleLogFilters);
    when(mockConfig.getServerLogFilters()).thenReturn(sampleLogFilters);

    FilterParams expectedParams =
        FilterParams.create(false, 0, 0);
    FilterParams clientResultParams1
        = configFilterHelper.logRpcMethod("service3/Method3", true);
    assertThat(clientResultParams1).isEqualTo(expectedParams);

    FilterParams clientResultParams2
        = configFilterHelper.logRpcMethod("service2/Method1", true);
    assertThat(clientResultParams2).isEqualTo(expectedParams);

    FilterParams serverResultParams
        = configFilterHelper.logRpcMethod("service2/Method1", false);
    assertThat(serverResultParams).isEqualTo(expectedParams);
  }

  @Test
  public void checkMethodToBeLoggedConditional() {
    when(mockConfig.getClientLogFilters()).thenReturn(configLogFilters);
    when(mockConfig.getServerLogFilters()).thenReturn(configLogFilters);

    FilterParams expectedParams =
        FilterParams.create(true, 1024, 1024);
    FilterParams resultParams
        = configFilterHelper.logRpcMethod("service1/Method2", true);
    assertThat(resultParams).isEqualTo(expectedParams);

    FilterParams expectedParamsWildCard =
        FilterParams.create(true, 2048, 1024);
    FilterParams resultParamsWildCard
        = configFilterHelper.logRpcMethod("service2/Method1", true);
    assertThat(resultParamsWildCard).isEqualTo(expectedParamsWildCard);

    FilterParams excludeParams =
        FilterParams.create(false, 0, 0);
    FilterParams serverResultParams
        = configFilterHelper.logRpcMethod("service4/method3", false);
    assertThat(serverResultParams).isEqualTo(excludeParams);
  }
}
