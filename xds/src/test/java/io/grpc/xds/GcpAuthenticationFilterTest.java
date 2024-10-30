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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.GcpAuthnFilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.TokenCacheConfig;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.StringMarshaller;
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
        .setCacheConfig(TokenCacheConfig.newBuilder().setCacheSize(UInt64Value.of(10)))
        .build();
    Any anyMessage = Any.pack(config);

    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    ConfigOrError<? extends Filter.FilterConfig> result = filter.parseFilterConfig(anyMessage);

    assertNotNull(result.config);
    assertNull(result.errorDetail);
    assertEquals(10L,
        ((GcpAuthenticationFilter.GcpAuthenticationConfig)
            result.config).getCacheSize().longValue());
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
    assertTrue(result.errorDetail.contains("cache_config.cache_size must be in the range"));
  }

  @Test
  public void testParseFilterConfig_withInvalidMessageType() {
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    Message invalidMessage = Mockito.mock(Message.class);
    ConfigOrError<? extends Filter.FilterConfig> result = filter.parseFilterConfig(invalidMessage);

    assertNull(result.config);
    assertNotNull(result.errorDetail);
    assertTrue(result.errorDetail.contains("Invalid config type"));
  }

  @Test
  public void testGetCallCredentials_addsToCache() throws Exception {
    ComputeEngineCredentials credentials = ComputeEngineCredentials.create();
    GcpAuthenticationFilter.LruCache<String, CallCredentials> cache =
        new GcpAuthenticationFilter.LruCache<>(10);

    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();
    CallCredentials callCredentials =
        filter.getCallCredentials(cache, "test-audience", credentials);

    assertNotNull(callCredentials);

    // Verify that the cached credentials are the same as those obtained via getCallCredentials
    CallCredentials cachedCredentials = cache.getOrInsert("test-audience", key -> null);
    assertSame(callCredentials, cachedCredentials);
  }

  @Test
  public void testBuildClientInterceptor_withCallCredentials() {
    GcpAuthenticationFilter.GcpAuthenticationConfig config =
        new GcpAuthenticationFilter.GcpAuthenticationConfig(10L);
    GcpAuthenticationFilter filter = new GcpAuthenticationFilter();

    // Create interceptor
    ClientInterceptor interceptor = filter.buildClientInterceptor(config, null, null, null);
    MethodDescriptor<String, String> methodDescriptor =
        MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(StringMarshaller.INSTANCE)
            .setResponseMarshaller(StringMarshaller.INSTANCE)
            .build();

    // Mock channel and capture CallOptions
    Channel mockChannel = Mockito.mock(Channel.class);
    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);

    // Execute interception
    interceptor.interceptCall(methodDescriptor, CallOptions.DEFAULT, mockChannel);

    // Capture and verify CallOptions for CallCredentials presence
    Mockito.verify(mockChannel).newCall(eq(methodDescriptor), callOptionsCaptor.capture());
    CallOptions capturedOptions = callOptionsCaptor.getValue();

    // Ensure that CallCredentials was added
    assertNotNull(capturedOptions.getCredentials());
  }

  @Test
  public void testLruCacheEviction() {
    GcpAuthenticationFilter.LruCache<String, String> cache =
        new GcpAuthenticationFilter.LruCache<>(2);
    cache.getOrInsert("first", key -> "value1");
    cache.getOrInsert("second", key -> "value2");

    // Add third entry, evicting the "first" entry
    cache.getOrInsert("third", key -> "value3");

    assertNull(cache.getOrInsert("first", key -> null));  // Should be evicted
    assertEquals("value2", cache.getOrInsert("second", key -> null)); // Should remain
    assertEquals("value3", cache.getOrInsert("third", key -> null)); // Should remain

    // Add fourth entry, evicting "second" now
    cache.getOrInsert("fourth", key -> "value4");

    assertNull(cache.getOrInsert("second", key -> null));  // Should be evicted
    assertEquals("value3", cache.getOrInsert("third", key -> null));  // Should remain
    assertEquals("value4", cache.getOrInsert("fourth", key -> null));  // Should remain
  }
}
