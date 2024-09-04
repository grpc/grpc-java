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

package io.grpc.xds.internal.matchers;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.xds.internal.MetadataHelper;

/** Unified Matcher API: xds.type.matcher.v3.CelMatcher. */
public class GrpcCelEnvironment  {
  private static final CelRuntime CEL_RUNTIME = CelRuntimeFactory
      .standardCelRuntimeBuilder()
      .setOptions(CelOptions.current().comprehensionMaxIterations(0).build())
      .build();

  private final CelRuntime.Program program;

  GrpcCelEnvironment(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    if (ast.getResultType() != SimpleType.BOOL) {
      throw new CelEvaluationException("Expected bool return type");
    }
    this.program = CEL_RUNTIME.createProgram(ast);
  }

  public boolean eval(ServerCall<?, ?> serverCall, Metadata metadata) {
    ImmutableMap.Builder<String, Object> requestBuilder = ImmutableMap.<String, Object>builder()
        .put("method", "POST")
        .put("host", Strings.nullToEmpty(serverCall.getAuthority()))
        .put("path", "/" + serverCall.getMethodDescriptor().getFullMethodName())
        .put("headers", MetadataHelper.metadataToHeaders(metadata));
    // TODO(sergiitk): handle other pseudo-headers
    try {
      return (boolean) program.eval(ImmutableMap.of("request", requestBuilder.build()));
    } catch (CelEvaluationException e) {
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }
}
