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

import com.google.common.base.Splitter;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelOptions;
import dev.cel.common.CelRuntimeException;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelVariableResolver;
import io.grpc.Status;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Unified Matcher API: xds.type.matcher.v3.CelMatcher. */
public class GrpcCelEnvironment  {
  private static final CelOptions CEL_OPTIONS = CelOptions
      .current()
      .comprehensionMaxIterations(0)
      .resolveTypeDependencies(false)
      .build();
  private static final CelRuntime CEL_RUNTIME = CelRuntimeFactory
      .standardCelRuntimeBuilder()
      .setOptions(CEL_OPTIONS)
      .build();

  private final CelRuntime.Program program;

  GrpcCelEnvironment(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    if (ast.getResultType() != SimpleType.BOOL) {
      throw new CelEvaluationException("Expected bool return type");
    }
    this.program = CEL_RUNTIME.createProgram(ast);
  }

  public boolean eval(HttpMatchInput httpMatchInput) {
    try {
      GrpcCelVariableResolver requestResolver = new GrpcCelVariableResolver(httpMatchInput);
      return (boolean) program.eval(requestResolver);
    } catch (CelEvaluationException | ClassCastException e) {
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  static class GrpcCelVariableResolver implements CelVariableResolver {
    private static final Splitter SPLITTER = Splitter.on('.').limit(2);
    private final HttpMatchInput httpMatchInput;

    GrpcCelVariableResolver(HttpMatchInput httpMatchInput) {
      this.httpMatchInput = httpMatchInput;
    }

    @Override
    public Optional<Object> find(String name) {
      List<String> components = SPLITTER.splitToList(name);
      if (components.size() < 2 || !components.get(0).equals("request")) {
        return Optional.empty();
      }
      return Optional.ofNullable(getRequestField(components.get(1)));
    }

    @Nullable
    private Object getRequestField(String requestField) {
      switch (requestField) {
        case "headers":
          return httpMatchInput.getHeadersWrapper();
        case "host":
          return httpMatchInput.getHost();
        case "id":
          return httpMatchInput.getHeadersWrapper().get("x-request-id");
        case "method":
          return httpMatchInput.getMethod();
        case "path":
        case "url_path":
          return httpMatchInput.getPath();
        case "query":
          return "";
        case "referer":
          return httpMatchInput.getHeadersWrapper().get("referer");
        case "useragent":
          return httpMatchInput.getHeadersWrapper().get("user-agent");
        default:
          // Throwing instead of Optional.empty() prevents evaluation non-boolean result type
          // when comparing unknown fields, f.e. `request.protocol == 'HTTP'` will silently
          // fail because `null == "HTTP" is not a valid CEL operation.
          throw new CelRuntimeException(
              // Similar to dev.cel.runtime.DescriptorMessageProvider#selectField
              new IllegalArgumentException("request." + requestField),
              CelErrorCode.ATTRIBUTE_NOT_FOUND);
      }
    }
  }
}
