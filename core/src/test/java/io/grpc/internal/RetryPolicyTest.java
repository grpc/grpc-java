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

import static java.lang.Double.parseDouble;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.grpc.MethodDescriptor;
import io.grpc.Status.Code;
import io.grpc.internal.ManagedChannelImpl.RetryPolicies;
import io.grpc.internal.RetriableStream.RetryPolicy;
import io.grpc.testing.TestMethodDescriptors;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for RetryPolicy. */
@RunWith(JUnit4.class)
public class RetryPolicyTest {

  @Test
  public void getRetryPolicies() throws Exception {
    Reader reader = null;
    try {
      reader = new InputStreamReader(
          RetryPolicyTest.class.getResourceAsStream(
              "/io/grpc/internal/test_retry_service_config.json"),
          "UTF-8");
      JsonObject serviceConfig = new JsonParser().parse(reader).getAsJsonObject();
      RetryPolicies retryPolicies = ManagedChannelImpl.getRetryPolicies(serviceConfig);
      assertNotNull(retryPolicies);

      MethodDescriptor.Builder<Void, Void> builder = TestMethodDescriptors.voidMethod().toBuilder();

      assertEquals(
          RetryPolicy.DEFAULT,
          retryPolicies.get(builder.setFullMethodName("not/exist").build()));
      assertEquals(
          RetryPolicy.DEFAULT,
          retryPolicies.get(builder.setFullMethodName("not_exist/Foo1").build()));

      assertEquals(
          new RetryPolicy(
              3, parseDouble("2.1"), parseDouble("2.2"), parseDouble("3"),
              Arrays.asList(Code.UNAVAILABLE, Code.RESOURCE_EXHAUSTED)),
          retryPolicies.get(builder.setFullMethodName("SimpleService1/not_exist").build()));
      assertEquals(
          new RetryPolicy(
              4, parseDouble(".1"), parseDouble("1"), parseDouble("2"),
              Arrays.asList(Code.UNAVAILABLE)),
          retryPolicies.get(builder.setFullMethodName("SimpleService1/Foo1").build()));

      assertEquals(
          RetryPolicy.DEFAULT,
          retryPolicies.get(builder.setFullMethodName("SimpleService2/not_exist").build()));
      assertEquals(
          new RetryPolicy(
              4, parseDouble(".1"), parseDouble("1"), parseDouble("2"),
              Arrays.asList(Code.UNAVAILABLE)),
          retryPolicies.get(builder.setFullMethodName("SimpleService2/Foo2").build()));
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }
}
