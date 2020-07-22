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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for evaluate argument. */
@RunWith(JUnit4.class)
public class EvaluateArgsTest<ReqT,RespT> {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private EvaluateArgs<ReqT,RespT> args;

  private AuthorizationEngine<ReqT,RespT> engine;
  private Metadata metadata;
  private ImmutableMap<String, Object> attributesMap;

  @Before
  public void setup() {
    // Set up metadata.
    metadata = new Metadata();
    // Set up CEL engine.
    RBAC rbacAllow = RBAC.newBuilder()
        .setAction(RBAC.Action.ALLOW)
        .build();
    List<RBAC> rbacList = new ArrayList<>(Arrays.asList(new RBAC[] {rbacAllow}));
    engine = new AuthorizationEngine<>(ImmutableList.copyOf(rbacList));
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
        .build();
    // Set up evaluate args.
    when(args.getRequestUrlPath()).thenReturn("package.service/method");
    when(args.getRequestHost()).thenReturn("fooapi.googleapis.com");
    when(args.getRequestMethod()).thenReturn("GET");
    when(args.getRequestHeaders()).thenReturn(metadata);
    when(args.getSourceAddress()).thenReturn("1.2.3.4");
    when(args.getSourcePort()).thenReturn(5050);
    when(args.getDestinationAddress()).thenReturn("4.3.2.1");
    when(args.getDestinationPort()).thenReturn(8080);
    when(args.getConnectionUriSanPeerCertificate()).thenReturn("foo");
  }

  @Test
  public void testExtractFields() {
    ImmutableMap<String, Object> attributes = engine.extractFields(args);
    assertEquals(attributesMap, attributes);
    verify(args, times(1)).getRequestUrlPath();
    verify(args, times(1)).getRequestHost();
    verify(args, times(1)).getRequestMethod();
    verify(args, times(1)).getRequestHeaders();
    verify(args, times(1)).getSourceAddress();
    verify(args, times(1)).getSourcePort();
    verify(args, times(1)).getDestinationAddress();
    verify(args, times(1)).getDestinationPort();
    verify(args, times(1)).getConnectionUriSanPeerCertificate();
  }
}
