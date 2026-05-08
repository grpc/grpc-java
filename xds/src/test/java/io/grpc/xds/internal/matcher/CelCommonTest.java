/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.matcher;

import static org.junit.Assert.fail;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelCommonTest {
  private static CelCompiler COMPILER;

  @BeforeClass
  public static void setupCompiler() {
    COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("request", SimpleType.DYN)
        .addVar("unknown_var", SimpleType.STRING)
        .build();
  }

  private void assertAllowed(String expression) throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
    CelCommon.checkAllowedReferences(ast);
  }

  private void assertDisallowed(String expression) throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
    try {
      CelCommon.checkAllowedReferences(ast);
      fail("Should have thrown IllegalArgumentException for expression: " + expression);
    } catch (IllegalArgumentException e) {
      // Expected
    }
  }

  @Test
  public void checkAllowedReferences_variables() throws Exception {
    assertAllowed("request == 'foo'");
    assertDisallowed("unknown_var == 'foo'");
  }

  @Test
  public void checkAllowedReferences_operators() throws Exception {
    assertAllowed("request == 'foo'");
    assertAllowed("request != 'foo'");
    assertAllowed("1 < 2");
    assertAllowed("1 <= 2");
    assertAllowed("1 > 2");
    assertAllowed("1 >= 2");
    assertAllowed("1 + 2 == 3");
    assertAllowed("1 - 2 == -1");
    assertAllowed("1 * 2 == 2");
    assertAllowed("1 / 2 == 0");
    assertAllowed("1 % 2 == 1");
    assertAllowed("true && false == false");
    assertAllowed("true || false == true");
    assertAllowed("!true == false");
  }

  @Test
  public void checkAllowedReferences_indexing() throws Exception {
    assertAllowed("request['key'] == 'val'");
  }

  @Test
  public void checkAllowedReferences_functions() throws Exception {
    assertAllowed("size('foo') == 3");
    assertAllowed("'foo'.matches('.*')");
    assertAllowed("'foo'.contains('o')");
    assertAllowed("'foo'.startsWith('f')");
    assertAllowed("'foo'.endsWith('o')");
    assertAllowed("int(1) == 1");
    assertAllowed("uint(1) == 1u");
    assertAllowed("double(1) == 1.0");
    
    // Disallowed functions / overloads
    assertDisallowed("string(1) == '1'");
    assertDisallowed("'a' + 'b' == 'ab'");
    assertDisallowed("[1] + [2] == [1, 2]");
  }

  @Test
  public void checkAllowedReferences_additionalFunctions() throws Exception {
    assertAllowed("timestamp('2026-04-20T00:00:00Z') != timestamp('2026-04-21T00:00:00Z')");
    assertAllowed("duration('1s') != duration('2s')");
    assertAllowed("'foo' in ['foo', 'bar']");
    assertAllowed("bytes('foo') == b'foo'");
    assertAllowed("bool('true') == true");
  }

  @Test
  public void checkAllowedReferences_negation() throws Exception {
    assertAllowed("-(1) == -1");
    assertAllowed("-(1.0) == -1.0");
  }
}
