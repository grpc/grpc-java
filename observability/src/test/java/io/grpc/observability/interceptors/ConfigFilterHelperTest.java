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
import io.grpc.observability.ObservabilityConfig;
import io.grpc.observability.ObservabilityConfig.LogFilter;
import io.grpc.observability.interceptors.ConfigFilterHelper.MethodFilterParams;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

public class ConfigFilterHelperTest {

  private static List<LogFilter> logFilterList = Stream.of(
          new LogFilter("service1/Method2",1024,1024),
          new LogFilter("service2/*",2048,1024),
          new LogFilter("*",128,128),
          new LogFilter("service2/*",2048,1024))
      .collect(Collectors.toList());
  private static final ImmutableList<LogFilter> configLogFilters =
      ImmutableList.copyOf(logFilterList);

  private static final ImmutableList<EventType> configEventTypes =
      ImmutableList.of(
          EventType.GRPC_CALL_REQUEST_HEADER,
          EventType.GRPC_CALL_HALF_CLOSE,
          EventType.GRPC_CALL_TRAILER);

  private ObservabilityConfig mockConfig;
  private ConfigFilterHelper configFilterHelper;

  @Before
  public void setup() throws Exception {
    mockConfig = mock(ObservabilityConfig.class);
    configFilterHelper = new ConfigFilterHelper(mockConfig);
  }

  @Test
  public void disableCloudLogging_emptyLogFilters() {
    configFilterHelper.setEmptyFilter();
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
    configFilterHelper.setEmptyFilter();
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
    configFilterHelper.setEmptyFilter();
    when(mockConfig.isEnableCloudLogging()).thenReturn(true);
    when(mockConfig.getLogFilters()).thenReturn(configLogFilters);
    when(mockConfig.getEventTypes()).thenReturn(configEventTypes);

    configFilterHelper.setMethodOrServiceFilterMaps();
    configFilterHelper.setEventFilterSet();

    assertTrue(configFilterHelper.globalLog);
    MethodFilterParams expectedGlobalParams =
        new MethodFilterParams(true, 128, 128);
    assertThat(configFilterHelper.globalParams.log)
        .isEqualTo(expectedGlobalParams.log);
    assertThat(configFilterHelper.globalParams.headerBytes)
        .isEqualTo(expectedGlobalParams.headerBytes);
    assertThat(configFilterHelper.globalParams.messageBytes)
        .isEqualTo(expectedGlobalParams.messageBytes);

    assertTrue(configFilterHelper.methodOrServiceFilterPresent);
    Map<String, MethodFilterParams> expectedServiceFilters = new HashMap<>();
    expectedServiceFilters.put("service2",
        new MethodFilterParams(true, 2048, 1024));

    assertThat(configFilterHelper.perServiceFilters).hasSize(1);
    assertThat(configFilterHelper.perServiceFilters.get("service2").log)
        .isEqualTo(expectedServiceFilters.get("service2").log);
    assertThat(configFilterHelper.perServiceFilters.get("service2").headerBytes)
        .isEqualTo(expectedServiceFilters.get("service2").headerBytes);
    assertThat(configFilterHelper.perServiceFilters.get("service2").messageBytes)
        .isEqualTo(expectedServiceFilters.get("service2").messageBytes);

    Map<String, MethodFilterParams> expectedMethodFilters = new HashMap<>();
    expectedMethodFilters.put("service1/Method2",
        new MethodFilterParams(true, 1024, 1024));

    assertThat(configFilterHelper.perMethodFilters).hasSize(1);
    assertThat(configFilterHelper.perMethodFilters.get("service1/Method2").log)
        .isEqualTo(expectedMethodFilters.get("service1/Method2").log);
    assertThat(configFilterHelper.perMethodFilters.get("service1/Method2").headerBytes)
        .isEqualTo(expectedMethodFilters.get("service1/Method2").headerBytes);
    assertThat(configFilterHelper.perMethodFilters.get("service1/Method2").messageBytes)
        .isEqualTo(expectedMethodFilters.get("service1/Method2").messageBytes);

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
    configFilterHelper.setEmptyFilter();
    when(mockConfig.getLogFilters()).thenReturn(sampleLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    String fullMethodName = "service1/Method6";
    MethodFilterParams expectedParams =
        configFilterHelper.globalParams;
    MethodFilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(fullMethodName);

    assertEquals(resultParams.log, expectedParams.log);
    assertEquals(resultParams.headerBytes, expectedParams.headerBytes);
    assertEquals(resultParams.messageBytes, expectedParams.messageBytes);
  }

  @Test
  public void checkMethodNotToBeLogged() {
    List<LogFilter> sampleFilters = Stream.of(
            new LogFilter("service1/Method2", 1024, 1024),
            new LogFilter("service2/*", 2048, 1024))
        .collect(Collectors.toList());
    ImmutableList<LogFilter> sampleLogFilters =
        ImmutableList.copyOf(sampleFilters);
    configFilterHelper.setEmptyFilter();
    when(mockConfig.getLogFilters()).thenReturn(sampleLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    String fullMethodName = "service3/Method3";
    MethodFilterParams expectedParams =
        new MethodFilterParams(false, 0, 0);
    MethodFilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(fullMethodName);

    assertEquals(resultParams.log, expectedParams.log);
    assertEquals(resultParams.headerBytes, expectedParams.headerBytes);
    assertEquals(resultParams.messageBytes, expectedParams.messageBytes);
  }

  @Test
  public void checkMethodToBeLoggedConditional() {
    configFilterHelper.setEmptyFilter();
    when(mockConfig.getLogFilters()).thenReturn(configLogFilters);
    configFilterHelper.setMethodOrServiceFilterMaps();

    String fullMethodName = "service1/Method2";
    MethodFilterParams expectedParams =
        new MethodFilterParams(true, 1024, 1024);
    MethodFilterParams resultParams
        = configFilterHelper.isMethodToBeLogged(fullMethodName);

    assertEquals(resultParams.log, expectedParams.log);
    assertEquals(resultParams.headerBytes, expectedParams.headerBytes);
    assertEquals(resultParams.messageBytes, expectedParams.messageBytes);

    String serviceNameWildcard = "service2/*";
    MethodFilterParams expectedParamsWildCard =
        new MethodFilterParams(true, 2048, 1024);
    MethodFilterParams resultParamsWildCard
        = configFilterHelper.isMethodToBeLogged(serviceNameWildcard);

    assertEquals(resultParamsWildCard.log, expectedParamsWildCard.log);
    assertEquals(resultParamsWildCard.headerBytes, expectedParamsWildCard.headerBytes);
    assertEquals(resultParamsWildCard.messageBytes, expectedParamsWildCard.messageBytes);
  }

  @Test
  public void checkEventToBeLogged() {
    configFilterHelper.setEmptyFilter();
    when(mockConfig.getEventTypes()).thenReturn(configEventTypes);
    configFilterHelper.setEventFilterSet();

    EventType logEventType = EventType.GRPC_CALL_REQUEST_HEADER;
    assertTrue(configFilterHelper.isEventToBeLogged(logEventType));

    EventType doNotLogEventType = EventType.GRPC_CALL_RESPONSE_MESSAGE;
    assertFalse(configFilterHelper.isEventToBeLogged(doNotLogEventType));
  }
}
