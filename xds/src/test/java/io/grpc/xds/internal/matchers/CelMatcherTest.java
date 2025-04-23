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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Strings;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.NoopServerCall;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.StringMarshaller;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelMatcherTest {
  // Construct the compilation and runtime environments.
  // These instances are immutable and thus trivially thread-safe and amenable to caching.
  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .addVar("request.path", SimpleType.STRING)
          .addVar("request.host", SimpleType.STRING)
          .addVar("request.method", SimpleType.STRING)
          .addVar("request.headers", MapType.create(SimpleType.STRING, SimpleType.STRING))
          // request.protocol is a legal input, but we don't set it in java.
          // TODO(sergiitk): add other fields not supported by gRPC
          .addVar("request.protocol", SimpleType.STRING)
          .setResultType(SimpleType.BOOL)
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .build();


  private static final HttpMatchInput fakeInput = new HttpMatchInput() {
    @Override
    public Metadata metadata() {
      return new Metadata();
    }

    @Override public ServerCall<?, ?> serverCall() {
      final MethodDescriptor<String, String> method =
          MethodDescriptor.<String, String>newBuilder()
              .setType(MethodType.UNKNOWN)
              .setFullMethodName("service/method")
              .setRequestMarshaller(new StringMarshaller())
              .setResponseMarshaller(new StringMarshaller())
              .build();
      return new NoopServerCall<String, String>() {
        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
          return method;
        }
      };
    }
  };

  @Test
  public void construct() throws Exception {
    CelAbstractSyntaxTree ast = celAst("1 == 1");
    CelMatcher matcher = CelMatcher.create(ast);
    assertThat(matcher.description()).isEqualTo("");

    String description = "Optional description";
    matcher = CelMatcher.create(ast, description);
    assertThat(matcher.description()).isEqualTo(description);
  }

  @Test
  public void progTrue() throws Exception {
    assertThat(newMatcher("request.method == 'POST'").test(fakeInput)).isTrue();
  }

  @Test
  public void unknownRequestProperty() throws Exception {
    CelMatcher matcher = newMatcher("request.protocol == 'Whatever'");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.ATTRIBUTE_NOT_FOUND);
  }

  @Test
  public void unknownHeader() throws Exception {
    CelMatcher matcher = newMatcher("request.headers['foo'] == 'bar'");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.ATTRIBUTE_NOT_FOUND);
  }

  @Test
  public void macros_comprehensionsDisabled() throws Exception {
    CelMatcher matcherWithComprehensions = newMatcher(
        "size(['foo', 'bar'].map(x, [request.headers[x], request.headers[x]])) == 1");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcherWithComprehensions.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.ITERATION_BUDGET_EXCEEDED);
  }

  @Test
  public void macros_hasEnabled() throws Exception {
    boolean result = newMatcher("has(request.headers.foo)").test(fakeInput);
    assertThat(result).isFalse();
  }

  @Test
  public void env_listConcatenationDisabled() throws Exception {
    CelMatcher matcher = newMatcher("size([1, 2] + [3, 4]) == 4");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.OVERLOAD_NOT_FOUND);
  }

  @Test
  public void env_stringConcatenationDisabled() throws Exception {
    CelMatcher matcher = newMatcher("'ab' + 'cd' == 'abcd'");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.OVERLOAD_NOT_FOUND);
  }

  @Test
  public void env_stringConversionDisabled() throws Exception {
    // TODO(sergiitk): [TEST] verify conversions to all types?
    CelMatcher matcher = newMatcher("string(3.14) == '3.14'");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.OVERLOAD_NOT_FOUND);
  }

  @Test
  public void env_regexProgramSize() throws Exception {
    String ten = "0123456780";

    // Positive case, program size <= 100.
    assertThat(newMatcher("matches('" + ten + "', '" + ten + "')").test(fakeInput)).isTrue();

    // Negative case, program size > 100.
    @SuppressWarnings("InlineMeInliner") // String.repeat() requires Java 11.
    String patternOverLimit = Strings.repeat(ten, 11); // Program Size = 112 in re2j 1.8 / cel 1.9.1

    CelMatcher matcher = newMatcher("matches('foo', '" + patternOverLimit + "')");
    Status status = assertThrows(StatusRuntimeException.class,
        () -> matcher.test(fakeInput)).getStatus();

    assertCelCauseErrorCode(status, CelErrorCode.INVALID_ARGUMENT);
    CelEvaluationException cause = (CelEvaluationException) status.getCause();
    assertThat(cause.getMessage()).contains(
        "Regex pattern exceeds allowed program size. Allowed: 100, Provided:");
  }

  private void assertCelCauseErrorCode(Status status, CelErrorCode expectedCelCode) {
    assertThat(status.getCode()).isEqualTo(Code.UNKNOWN);
    assertThat(status.getCause()).isInstanceOf(CelEvaluationException.class);

    CelEvaluationException cause = (CelEvaluationException) status.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(expectedCelCode);
  }

  private CelMatcher newMatcher(String expr) throws CelValidationException, CelEvaluationException {
    return CelMatcher.create(celAst(expr));
  }

  private CelAbstractSyntaxTree celAst(String expr) throws CelValidationException {
    return CEL_COMPILER.compile(expr).getAst();
  }
}
