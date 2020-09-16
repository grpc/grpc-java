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

package io.grpc.xds.internal.rbac.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.ServerCall;
import io.grpc.internal.GrpcAttributes;
import io.netty.channel.local.LocalAddress;
import java.net.SocketAddress;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for evaluate argument. */
@RunWith(JUnit4.class)
public class EvaluateArgsTest<ReqT,RespT> {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private ServerCall<ReqT,RespT> call;

  private EvaluateArgs args;
  private EvaluateArgs spyArgs;

  private Metadata metadata;
  private ImmutableMap<String, Object> attributesMap;

  @Before
  public void setup() {
    // Set up metadata.
    metadata = new Metadata();
    // Set up spyArgs.
    args = new EvaluateArgs(metadata, call);
    spyArgs = Mockito.spy(args);
    // Set up attributes map.
    attributesMap = ImmutableMap.<String, Object>builder()
        .put("request.url_path", "package.service/method")
        .put("request.host", "fooapi.googleapis.com")
        .put("request.method", "GET")
        .put("request.headers", metadata)
        .put("source.address", "1.2.3.4")
        .put("source.port", 5050)
        .put("destination.address", "4.3.2.1")
        .put("destination.port", 8080)
        .put("connection.uri_san_peer_certificate", "foo")
        .put("source.principal", "spiffe")
        .build();
    // Set up evaluate args.
    doReturn("package.service/method").when(spyArgs).getRequestUrlPath();
    doReturn("fooapi.googleapis.com").when(spyArgs).getRequestHost();
    doReturn("GET").when(spyArgs).getRequestMethod();
    doReturn(metadata).when(spyArgs).getRequestHeaders();
    doReturn("1.2.3.4").when(spyArgs).getSourceAddress();
    doReturn(5050).when(spyArgs).getSourcePort();
    doReturn("4.3.2.1").when(spyArgs).getDestinationAddress();
    doReturn(8080).when(spyArgs).getDestinationPort();
    doReturn("foo").when(spyArgs).getConnectionUriSanPeerCertificate();
    doReturn("spiffe").when(spyArgs).getSourcePrincipal();
  }

  @Test
  public void testGenerateEnvoyAttributes() {
    setup();
    ImmutableMap<String, Object> attributes = spyArgs.generateEnvoyAttributes();
    assertEquals(attributesMap, attributes);
    verify(spyArgs, times(1)).getRequestUrlPath();
    verify(spyArgs, times(1)).getRequestHost();
    verify(spyArgs, times(1)).getRequestMethod();
    verify(spyArgs, times(1)).getRequestHeaders();
    verify(spyArgs, times(1)).getSourceAddress();
    verify(spyArgs, times(1)).getSourcePort();
    verify(spyArgs, times(1)).getDestinationAddress();
    verify(spyArgs, times(1)).getDestinationPort();
    verify(spyArgs, times(1)).getConnectionUriSanPeerCertificate();
    verify(spyArgs, times(1)).getSourcePrincipal();
  }

  @Test
  public void testEvaluateArgsAccessorFunctions() {
    // Set up args and call.
    args = new EvaluateArgs(new Metadata(), call);
    SocketAddress localAddr = new LocalAddress("local_addr");
    SocketAddress remoteAddr = new LocalAddress("remote_addr");
    Attributes attrs = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddr)
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddr)
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.NONE)
        .build();
    when(call.getAttributes()).thenReturn(attrs);
    when(call.getAuthority()).thenReturn("fooapi.googleapis.com");
    // Check the behavior of accessor functions.
    assertEquals(args.getRequestHost(), "fooapi.googleapis.com");
    assertNotNull(args.getRequestHeaders());
    assertEquals(args.getSourcePort(), 0);
    assertEquals(args.getDestinationPort(), 0);
    assertEquals(args.getSourceAddress(), "local:remote_addr");
    assertEquals(args.getDestinationAddress(), "local:local_addr");
    assertEquals(args.getConnectionUriSanPeerCertificate(), "placeholder");
    assertEquals(args.getSourcePrincipal(), "placeholder");
  }
}
