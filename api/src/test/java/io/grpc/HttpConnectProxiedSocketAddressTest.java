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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.testing.EqualsTester;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HttpConnectProxiedSocketAddressTest {

  private final InetSocketAddress proxyAddress = 
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
  private final InetSocketAddress targetAddress = 
      InetSocketAddress.createUnresolved("example.com", 443);

  @Test
  public void buildWithAllFields() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Custom-Header", "custom-value");
    headers.put("Proxy-Authorization", "Bearer token");

    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers)
        .setUsername("user")
        .setPassword("pass")
        .build();

    assertThat(address.getProxyAddress()).isEqualTo(proxyAddress);
    assertThat(address.getTargetAddress()).isEqualTo(targetAddress);
    assertThat(address.getHeaders()).hasSize(2);
    assertThat(address.getHeaders()).containsEntry("X-Custom-Header", "custom-value");
    assertThat(address.getHeaders()).containsEntry("Proxy-Authorization", "Bearer token");
    assertThat(address.getUsername()).isEqualTo("user");
    assertThat(address.getPassword()).isEqualTo("pass");
  }

  @Test
  public void buildWithoutOptionalFields() {
    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .build();

    assertThat(address.getProxyAddress()).isEqualTo(proxyAddress);
    assertThat(address.getTargetAddress()).isEqualTo(targetAddress);
    assertThat(address.getHeaders()).isEmpty();
    assertThat(address.getUsername()).isNull();
    assertThat(address.getPassword()).isNull();
  }

  @Test
  public void buildWithEmptyHeaders() {
    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(Collections.emptyMap())
        .build();

    assertThat(address.getHeaders()).isEmpty();
  }

  @Test
  public void headersAreImmutable() {
    Map<String, String> headers = new HashMap<>();
    headers.put("key1", "value1");

    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers)
        .build();

    headers.put("key2", "value2");

    assertThat(address.getHeaders()).hasSize(1);
    assertThat(address.getHeaders()).containsEntry("key1", "value1");
    assertThat(address.getHeaders()).doesNotContainKey("key2");
  }

  @Test
  public void returnedHeadersAreUnmodifiable() {
    Map<String, String> headers = new HashMap<>();
    headers.put("key", "value");

    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers)
        .build();

    assertThrows(UnsupportedOperationException.class,
        () -> address.getHeaders().put("newKey", "newValue"));
  }

  @Test
  public void nullHeadersThrowsException() {
    assertThrows(NullPointerException.class,
        () -> HttpConnectProxiedSocketAddress.newBuilder()
            .setProxyAddress(proxyAddress)
            .setTargetAddress(targetAddress)
            .setHeaders(null)
            .build());
  }

  @Test
  public void equalsAndHashCode() {
    Map<String, String> headers1 = new HashMap<>();
    headers1.put("header", "value");

    Map<String, String> headers2 = new HashMap<>();
    headers2.put("header", "value");

    Map<String, String> differentHeaders = new HashMap<>();
    differentHeaders.put("different", "header");

    new EqualsTester()
        .addEqualityGroup(
            HttpConnectProxiedSocketAddress.newBuilder()
                .setProxyAddress(proxyAddress)
                .setTargetAddress(targetAddress)
                .setHeaders(headers1)
                .setUsername("user")
                .setPassword("pass")
                .build(),
            HttpConnectProxiedSocketAddress.newBuilder()
                .setProxyAddress(proxyAddress)
                .setTargetAddress(targetAddress)
                .setHeaders(headers2)
                .setUsername("user")
                .setPassword("pass")
                .build())
        .addEqualityGroup(
            HttpConnectProxiedSocketAddress.newBuilder()
                .setProxyAddress(proxyAddress)
                .setTargetAddress(targetAddress)
                .setHeaders(differentHeaders)
                .setUsername("user")
                .setPassword("pass")
                .build())
        .addEqualityGroup(
            HttpConnectProxiedSocketAddress.newBuilder()
                .setProxyAddress(proxyAddress)
                .setTargetAddress(targetAddress)
                .build())
        .testEquals();
  }

  @Test
  public void toStringContainsHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Test", "test-value");

    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers)
        .setUsername("user")
        .setPassword("secret")
        .build();

    String toString = address.toString();
    assertThat(toString).contains("headers");
    assertThat(toString).contains("X-Test");
    assertThat(toString).contains("hasPassword=true");
    assertThat(toString).doesNotContain("secret");
  }

  @Test
  public void toStringWithoutPassword() {
    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .build();

    String toString = address.toString();
    assertThat(toString).contains("hasPassword=false");
  }

  @Test
  public void hashCodeDependsOnHeaders() {
    Map<String, String> headers1 = new HashMap<>();
    headers1.put("header", "value1");

    Map<String, String> headers2 = new HashMap<>();
    headers2.put("header", "value2");

    HttpConnectProxiedSocketAddress address1 = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers1)
        .build();

    HttpConnectProxiedSocketAddress address2 = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers2)
        .build();

    assertNotEquals(address1.hashCode(), address2.hashCode());
  }

  @Test
  public void multipleHeadersSupported() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Header-1", "value1");
    headers.put("X-Header-2", "value2");
    headers.put("X-Header-3", "value3");

    HttpConnectProxiedSocketAddress address = HttpConnectProxiedSocketAddress.newBuilder()
        .setProxyAddress(proxyAddress)
        .setTargetAddress(targetAddress)
        .setHeaders(headers)
        .build();

    assertThat(address.getHeaders()).hasSize(3);
    assertThat(address.getHeaders()).containsEntry("X-Header-1", "value1");
    assertThat(address.getHeaders()).containsEntry("X-Header-2", "value2");
    assertThat(address.getHeaders()).containsEntry("X-Header-3", "value3");
  }
}

