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
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.ServerCall;
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
  private ServerCall<ReqT,RespT> call;

  private EvaluateArgs<ReqT,RespT> args;
  
  @Test
  public void testEvaluateArgsAccessorFunctions() {
    args = new EvaluateArgs<ReqT,RespT>(new Metadata(), call);
    when(call.getAuthority()).thenReturn("fooapi.googleapis.com");
    assertNotNull(args.getCall());
    assertNotNull(args.getHeaders());
    assertEquals(args.getRequestHost(), "fooapi.googleapis.com");
    assertNotNull(args.getRequestHeaders());
    assertEquals(args.getSourcePort(), 0);
    assertEquals(args.getDestinationPort(), 0);
    assertEquals(args.getConnectionUriSanPeerCertificate(), "placeholder");
    assertEquals(args.getSourcePrincipal(), "placeholder");
  }
}
