/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Code;
import io.grpc.serviceconfig.MethodConfig;
import io.grpc.serviceconfig.MethodConfig.Name;
import io.grpc.serviceconfig.ServiceConfig;
import io.grpc.serviceconfig.ServiceConfig.LoadBalancingPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ServiceConfigUtil}.
 */
@RunWith(JUnit4.class)
public class ServiceConfigUtilTest {

  @Test
  public void getMethodConfigFromServiceConfig() throws Exception {
    List<Map<String, Object>> expectedMethodConfigs = new ArrayList<Map<String, Object>>();
    ServiceConfig.Builder serviceConfigBuilder = ServiceConfig.newBuilder();

    MethodConfig.Builder methodConfig1 = MethodConfig.newBuilder()
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.2"))
        .setWaitForReady(BoolValue.of(true));
    serviceConfigBuilder.addMethodConfig(methodConfig1);
    expectedMethodConfigs.add(protobufToJson(methodConfig1));

    MethodConfig.Builder methodConfig2 = MethodConfig.newBuilder()
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.3"));
    serviceConfigBuilder.addMethodConfig(methodConfig2);
    expectedMethodConfigs.add(protobufToJson(methodConfig2));

    MethodConfig.Builder methodConfig3 = MethodConfig.newBuilder()
        .addName(Name.newBuilder().setService("fooService2").setMethod("barMethod2.1"));
    serviceConfigBuilder.addMethodConfig(methodConfig3);
    expectedMethodConfigs.add(protobufToJson(methodConfig3));

    MethodConfig.Builder methodConfig4 = MethodConfig.newBuilder()
        .addName(Name.newBuilder().setService("fooService2"));
    serviceConfigBuilder.addMethodConfig(methodConfig4);
    expectedMethodConfigs.add(protobufToJson(methodConfig4));

    MethodConfig.Builder methodConfig5 = MethodConfig.newBuilder()
        .addName(Name.newBuilder().setService("fooService3"));
    serviceConfigBuilder.addMethodConfig(methodConfig5);
    expectedMethodConfigs.add(protobufToJson(methodConfig5));

    serviceConfigBuilder.setLoadBalancingPolicy(LoadBalancingPolicy.ROUND_ROBIN);

    Map<String, Object> serviceConfig = protobufToJson(serviceConfigBuilder);
    List<Map<String, Object>> methodConfigs =
        ServiceConfigUtil.getMethodConfigFromServiceConfig(serviceConfig);

    assertEquals(expectedMethodConfigs, methodConfigs);
  }

  @Test
  public void getLoadBalancingPolicyFromServiceConfig() throws Exception {
    LoadBalancingPolicy loadBalancingPolicy = LoadBalancingPolicy.ROUND_ROBIN;
    ServiceConfig.Builder serviceConfigBuilder = ServiceConfig.newBuilder();
    serviceConfigBuilder
        .addMethodConfig(MethodConfig.newBuilder()
            .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
            .setWaitForReady(BoolValue.of(true)))
        .setLoadBalancingPolicy(loadBalancingPolicy);

    Map<String, Object> serviceConfig = protobufToJson(serviceConfigBuilder);
    String actualLoadBalancingPolicy =
        ServiceConfigUtil.getLoadBalancingPolicyFromServiceConfig(serviceConfig);

    assertEquals(loadBalancingPolicy.toString(), actualLoadBalancingPolicy);
  }

  // TODO(zdapeng): getRetryThrottling from service config

  @Test
  public void getNameListFromMethodConfig() throws Exception {
    List<Map<String, Object>> expectedNames = new ArrayList<Map<String, Object>>();
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();

    Name.Builder name1 = Name.newBuilder().setService("fooService1").setMethod("barMethod1.1");
    methodConfigBuilder.addName(name1);
    expectedNames.add(protobufToJson(name1));

    Name.Builder name2 = Name.newBuilder().setService("fooService1").setMethod("barMethod1.2");
    methodConfigBuilder.addName(name2);
    expectedNames.add(protobufToJson(name2));

    Name.Builder name3 = Name.newBuilder().setService("fooService2").setMethod("barMethod2.1");
    methodConfigBuilder.addName(name3);
    expectedNames.add(protobufToJson(name3));

    Name.Builder name4 = Name.newBuilder().setService("fooService2");
    methodConfigBuilder.addName(name4);
    expectedNames.add(protobufToJson(name4));

    Name.Builder name5 = Name.newBuilder().setService("fooService3");
    methodConfigBuilder.addName(name5);
    expectedNames.add(protobufToJson(name5));

    methodConfigBuilder
        .setWaitForReady(BoolValue.of(true));

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);
    List<Map<String, Object>> names = ServiceConfigUtil.getNameListFromMethodConfig(methodConfig);

    assertEquals(expectedNames, names);
  }

  @Test
  public void getWaitForReadyFromMethodConfig() throws Exception {
    boolean waitForReady = true;
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();
    methodConfigBuilder
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .addName(Name.newBuilder().setService("fooService2"))
        .setWaitForReady(BoolValue.of(waitForReady));

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);

    assertEquals(waitForReady, ServiceConfigUtil.getWaitForReadyFromMethodConfig(methodConfig));
  }

  @Test
  public void getTimeoutFromMethodConfig() throws Exception {
    int seconds = 345;
    int nanos = 678;
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();
    methodConfigBuilder
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .addName(Name.newBuilder().setService("fooService2"))
        .setTimeout(Duration.newBuilder().setSeconds(seconds).setNanos(nanos));

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);

    Long expectedTimeout = TimeUnit.SECONDS.toNanos(seconds) + nanos;
    assertEquals(
        expectedTimeout, ServiceConfigUtil.getTimeoutFromMethodConfig(methodConfig));
  }
  
  @Test
  public void getRetryPolicyFromMethodConfig() throws Exception {
    Integer maxAttempts = 3;
    int initialBackoffSecPart = 345;
    int initialBackoffNanoPart = 678;
    Float backoffMultiplier = 3.14f;
    int maxBackoffSecPart = 987;
    int maxBackoffNanoPart = 654;
    List<Code> retryableStatusCodes = Arrays.asList(Code.DATA_LOSS, Code.UNIMPLEMENTED);

    MethodConfig.RetryPolicy.Builder retryPolicy = MethodConfig.RetryPolicy.newBuilder()
        .setMaxAttempts(maxAttempts)
        .setInitialBackoff(Duration.newBuilder()
            .setSeconds(initialBackoffSecPart)
            .setNanos(initialBackoffNanoPart))
        .setBackoffMultiplier(backoffMultiplier)
        .setMaxBackoff(Duration.newBuilder()
            .setSeconds(maxBackoffSecPart)
            .setNanos(maxBackoffNanoPart))
        .addAllRetryableStatusCodes(retryableStatusCodes);
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();
    methodConfigBuilder
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .addName(Name.newBuilder().setService("fooService2"))
        .setRetryPolicy(retryPolicy);
    Map<String, Object> expectedRetryPolicy = protobufToJson(retryPolicy);

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);
    Map<String, Object> actualRetryPolicy =
        ServiceConfigUtil.getRetryPolicyFromMethodConfig(methodConfig);

    assertEquals(expectedRetryPolicy, actualRetryPolicy);
    assertEquals(maxAttempts, ServiceConfigUtil.getMaxAttemptsFromRetryPolicy(actualRetryPolicy));
    assertEquals(
        TimeUnit.SECONDS.toNanos(initialBackoffSecPart) + initialBackoffNanoPart,
        ServiceConfigUtil.getInitialBackoffNanosFromRetryPolicy(actualRetryPolicy).longValue());
    assertEquals(
        0,
        Float.compare(
            backoffMultiplier,
            ServiceConfigUtil.getBackoffMultiplierFromRetryPolicy(actualRetryPolicy).floatValue()));
    assertEquals(
        TimeUnit.SECONDS.toNanos(maxBackoffSecPart) + maxBackoffNanoPart,
        ServiceConfigUtil.getMaxBackoffNanosFromRetryPolicy(actualRetryPolicy).longValue());

    List<String> expectedRetryableStatusCodes = new ArrayList<String>();
    for (Code code : retryableStatusCodes) {
      expectedRetryableStatusCodes.add(code.toString());
    }
    assertEquals(
        expectedRetryableStatusCodes,
        ServiceConfigUtil.getRetryableStatusCodesFromRetryPolicy(actualRetryPolicy));
  }

  @Test
  public void getMaxRequestMessageBytesFromMethodConfig() throws Exception {
    Integer maxRequestMessageBytes = 321;
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();
    methodConfigBuilder
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .setMaxRequestMessageBytes(UInt32Value.of(maxRequestMessageBytes));

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);

    assertEquals(
        maxRequestMessageBytes,
        ServiceConfigUtil.getMaxRequestMessageBytesFromMethodConfig(methodConfig));
  }

  @Test
  public void getMaxResponseMessageBytesFromMethodConfig() throws Exception {
    Integer maxResponseMessageBytes = 321;
    MethodConfig.Builder methodConfigBuilder = MethodConfig.newBuilder();
    methodConfigBuilder
        .addName(Name.newBuilder().setService("fooService1").setMethod("barMethod1.1"))
        .setMaxResponseMessageBytes(UInt32Value.of(maxResponseMessageBytes));

    Map<String, Object> methodConfig = protobufToJson(methodConfigBuilder);

    assertEquals(
        maxResponseMessageBytes,
        ServiceConfigUtil.getMaxResponseMessageBytesFromMethodConfig(methodConfig));
  }

  // TODO(zdapeng): getHedgingPolicy form method config

  @Test
  public void getServiceFromName() throws Exception {
    String service = "fooService";
    String method = "barMethod";

    Name.Builder name1 = Name.newBuilder().setService(service).setMethod(method);
    Map<String, Object> name1Json = protobufToJson(name1);

    assertEquals(service, ServiceConfigUtil.getServiceFromName(name1Json));


    Name.Builder name2 = Name.newBuilder().setService(service);
    Map<String, Object> name2Json = protobufToJson(name2);

    assertEquals(service, ServiceConfigUtil.getServiceFromName(name2Json));
  }

  @Test
  public void getMethodFromName() throws Exception {
    String service = "fooService";
    String method = "barMethod";

    Name.Builder name1 = Name.newBuilder().setService(service).setMethod(method);
    Map<String, Object> name1Json = protobufToJson(name1);

    assertEquals(method, ServiceConfigUtil.getMethodFromName(name1Json));


    Name.Builder name2 = Name.newBuilder().setService(service);
    Map<String, Object> name2Json = protobufToJson(name2);

    assertEquals(null, ServiceConfigUtil.getMethodFromName(name2Json));
  }

  static Map<String, Object> protobufToJson(MessageOrBuilder proto) throws IOException {
    String jsonStr = JsonFormat.printer().print(proto);
    @SuppressWarnings("unchecked")
    Map<String, Object> jsonObj = (Map<String, Object>) JsonParser.parse(jsonStr);
    return jsonObj;
  }
}
