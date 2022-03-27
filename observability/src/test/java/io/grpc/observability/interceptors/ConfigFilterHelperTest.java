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

package io.grpc.observability.interceptors;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.grpc.MethodDescriptor;
import io.grpc.observability.ObservabilityConfig;
import io.grpc.observability.ObservabilityConfig.LogFilter;
import io.grpc.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.grpc.testing.TestMethodDescriptors;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

public class ConfigFilterHelperTest {

  private static final ImmutableList<LogFilter> configLogFilters =
      ImmutableList.of(
          new LogFilter("service1/Method2",1024,1024),
          new LogFilter("service2/*",2048,1024),
          new LogFilter("*",128,128),
          new LogFilter("service2/*",2048,1024));

  private static final ImmutableList<EventType> configEventTypes =
      ImmutableList.of(
          EventType.GRPC_CALL_REQUEST_HEADER,
          EventType.GRPC_CALL_HALF_CLOSE,
          EventType.GRPC_CALL_TRAILER);

  private MethodDescriptor.Builder<Void, Void> builder = TestMethodDescriptors.voidMethod()
      .toBuilder();
  private MethodDescriptor<Void, Void> method;

  private ObservabilityConfig mockConfig;
  private ConfigFilterHelper configFilterHelper;

  @Before
  public void setup() throws Exception {
    mockConfig = mock(ObservabilityConfig.class);
    configFilterHelper = new ConfigFilterHelper(mockConfig);
  }

  @Test
  public void disableCloudLogging_emptyLogFilters() {
    when(mockConfig.isEnableCloudLogging()).thenReturn(false);
    assertFalse(configFilterHelper.globalLog);
    assertFalse(configFilterHelper.methodOrServiceFilterPresent);
    assertThat(configFilterHelper.perServiceFilters).isEmpty();
    assertThat(configFilterHelper.perServiceFilters).isEmpty();
    assertThat(configFilterHelper.perMethodFilters).isEmpty();
    assertThat(configFilterHelper.logEventTypeSet).isEmpty();
  }

  @Test
  public void enableCloudLogging_emptyLogFilters() {
    when(mockConfig.isEnableCloudLogging()).thenReturn(true);
    when(mockConfig.getLogFilters()).thenReturn(null);
    when(mockConfig.getEventTypes()).thenReturn(null);
    configFilterHelper.setMethodOrServiceFilterMaps();
    configFilterHelper.setEventFilterSet();

    assertFalse(configFilterHelper.globalLog);
    assertFalse(configFilterHelper.methodOrServiceFilterPresent);
    assertThat(configFilterHelper.perServiceFilters).isEmpty();
    assertThat(configFilterHelper.perServiceFilters).isEmpty();
    assertThat(configFilterHelper.perMethodFilters).isEmpty();
    assertThat(configFilterHelper.logEventTypeSet).isEmpty();
  }

  @Test
  public void enableCloudLogging_withLogFilters() {
    when(mockConfig.isEnableCloudLogging()).thenReturn(true);
    when(mockConfig.getLogFilters()).thenReturn(configLogFilters);
    when(mockConfig.getEventTypes()).thenReturn(configEventTypes);

    configFilterHelper.setMethodOrServiceFilterMaps();
    configFilterHelper.setEventFilterSet();

    assertTrue(configFilterHelper.globalLog);
    FilterParams expectedGlobalParams =
        FilterParams.create(true, 128, 128);
    assertEquals(configFilterHelper.globalParams, expectedGlobalParams);

    assertTrue(configFilterHelper.methodOrServiceFilterPresent);

    Map<String, FilterParams> expectedServiceFilters = new HashMap<>();
    expectedServiceFilters.put("service2",
        FilterParams.create(true, 2048, 1024));
    assertEquals(configFilterHelper.perServiceFilters, expectedServiceFilters);

    Map<String, FilterParams> expectedMethodFilters = new HashMap<>();
    expectedMethodFilters.put("service1/Method2",
        FilterParams.create(true, 1024, 1024));
    assertEquals(configFilterHelper.perMethodFilters, expectedMethodFilters);

    Set<EventType> expectedlogEventTypeSet = ImmutableSet.copyOf(configEventTypes);
    assertEquals(configFilterHelper.logEventTypeSet, expectedlogEventTypeSet);
  }

  @Test
  public void checkMethodAlwaysLogged() {
    List<LogFilter> sampleFilters = Stream.of(
            new LogFilter("*", 4096, 4096))
        .collect(Collectors.toList());
    ImmutableList<LogFilter> sampleLogFilters =
        ImmutableList.copyOf(sampleFilters);
    when(mockConfig.getLogFilters()).thenReturn(sampleLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    FilterParams expectedParams =
        configFilterHelper.globalParams;
    method = builder.setFullMethodName("service1/Method6").build();
    FilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(method);
    assertEquals(resultParams, expectedParams);
  }

  @Test
  public void checkMethodNotToBeLogged() {
    List<LogFilter> sampleFilters = Stream.of(
            new LogFilter("service1/Method2", 1024, 1024),
            new LogFilter("service2/*", 2048, 1024))
        .collect(Collectors.toList());
    ImmutableList<LogFilter> sampleLogFilters =
        ImmutableList.copyOf(sampleFilters);
    when(mockConfig.getLogFilters()).thenReturn(sampleLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    FilterParams expectedParams =
        FilterParams.create(false, 0, 0);
    method = builder.setFullMethodName("service3/Method3").build();
    FilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(method);
    assertEquals(resultParams, expectedParams);
  }

  @Test
  public void checkMethodToBeLoggedConditional() {
    when(mockConfig.getLogFilters()).thenReturn(configLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    FilterParams expectedParams =
        FilterParams.create(true, 1024, 1024);
    method = builder.setFullMethodName("service1/Method2").build();
    FilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(method);
    assertEquals(resultParams, expectedParams);

    FilterParams expectedParamsWildCard =
        FilterParams.create(true, 2048, 1024);
    method = builder.setFullMethodName("service2/Method1").build();
    FilterParams resultParamsWildCard
        = configFilterHelper.isMethodToBeLogged(method);
    assertEquals(resultParamsWildCard, expectedParamsWildCard);
  }

  @Test
  public void checkEventToBeLogged() {
    when(mockConfig.getEventTypes()).thenReturn(configEventTypes);
    configFilterHelper.setEventFilterSet();

    EventType logEventType = EventType.GRPC_CALL_REQUEST_HEADER;
    assertTrue(configFilterHelper.isEventToBeLogged(logEventType));

    EventType doNotLogEventType = EventType.GRPC_CALL_RESPONSE_MESSAGE;
    assertFalse(configFilterHelper.isEventToBeLogged(doNotLogEventType));
  }
}
