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

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import io.grpc.Metadata;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelMatcherTest {
  // Construct the compilation and runtime environments.
  // These instances are immutable and thus trivially thread-safe and amenable to caching.
  private static final CelCompiler CEL_COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder().addVar("my_var", SimpleType.STRING).build();
  private static final CelValidationResult celProg1 =
      CEL_COMPILER.compile("type(my_var) == string");

  CelAbstractSyntaxTree ast1;
  CelMatcher matcher1;

  private static final HttpMatchInput fakeInput = new HttpMatchInput() {
    @Override
    public Metadata headers() {
      return new Metadata();
    }
  };

  @Before
  public void setUp() throws Exception {
    ast1 = celProg1.getAst();
    matcher1 = CelMatcher.create(ast1);
  }

  @Test
  public void construct() throws CelEvaluationException {
    assertThat(matcher1.description()).isEqualTo("");

    String description = "Optional description";
    CelMatcher matcher = CelMatcher.create(ast1, description);
    assertThat(matcher.description()).isEqualTo(description);
  }

  @Test
  public void testProgTrue() {
    assertThat(matcher1.test(fakeInput)).isTrue();
  }
}
