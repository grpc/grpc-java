/*
 * Copyright 2024 The gRPC Authors
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.TokenCacheConfig;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.testing.TestMethodDescriptors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class GcpAuthenticationFilterTest {

  @Test
  public void testParseFilterConfig_withValidConfig() {
    GcpAuthnFilterConfig config = GcpAuthnFilterConfig.newBuilder()
        .setCacheConfig(TokenCacheConfig.newBuilder().setCacheSize(UInt64Value.of(20)))
        .build();
    Any anyMessage = Any.pack(config);

    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    ConfigOrError<? extends Filter.FilterConfig> result = filter.parseFilterConfig(anyMessage);

    assertNotNull(result.config);
    assertNull(result.errorDetail);
    assertEquals(20L,
        ((GcpAuthenticationFilter.GcpAuthenticationConfig) result.config).getCacheSize());
  }

  @Test
  public void testParseFilterConfig_withZeroCacheSize() {
    GcpAuthnFilterConfig config = GcpAuthnFilterConfig.newBuilder()
        .setCacheConfig(TokenCacheConfig.newBuilder().setCacheSize(UInt64Value.of(0)))
        .build();
    Any anyMessage = Any.pack(config);

    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    ConfigOrError<? extends Filter.FilterConfig> result = filter.parseFilterConfig(anyMessage);

    assertNull(result.config);
    assertNotNull(result.errorDetail);
    assertTrue(result.errorDetail.contains("cache_config.cache_size must be greater than zero"));
  }

  @Test
  public void testParseFilterConfig_withInvalidMessageType() {
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    Message invalidMessage = Empty.getDefaultInstance();
    ConfigOrError<? extends Filter.FilterConfig> result = filter.parseFilterConfig(invalidMessage);

    assertNull(result.config);
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  @Test
  public void testClientInterceptor_createsAndReusesCachedCredentials() {
    GcpAuthenticationFilter.GcpAuthenticationConfig config =
        new GcpAuthenticationFilter.GcpAuthenticationConfig(10);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null, null);
    MethodDescriptor<Void, Void> methodDescriptor = TestMethodDescriptors.voidMethod();

    // Mock channel and capture CallOptions
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    // Execute interception twice to check caching
    interceptor.interceptCall(methodDescriptor, CallOptions.DEFAULT, mockChannel);
    interceptor.interceptCall(methodDescriptor, CallOptions.DEFAULT, mockChannel);

    // Capture and verify CallOptions for CallCredentials presence
    Mockito.verify(mockChannel, Mockito.times(2))
        .newCall(eq(methodDescriptor), callOptionsCaptor.capture());

    // Retrieve the CallOptions captured from both calls
    CallOptions firstCapturedOptions = callOptionsCaptor.getAllValues().get(0);
    CallOptions secondCapturedOptions = callOptionsCaptor.getAllValues().get(1);

    // Ensure that CallCredentials was added
    assertNotNull(firstCapturedOptions.getCredentials());
    assertNotNull(secondCapturedOptions.getCredentials());

    // Ensure that the CallCredentials from both calls are the same, indicating caching
    assertSame(firstCapturedOptions.getCredentials(), secondCapturedOptions.getCredentials());
  }
}
