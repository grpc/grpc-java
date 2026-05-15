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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelVariableResolver;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelStringExtractorTest {

  @Test
  public void extract_simpleString() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'foo'");
    CelVariableResolver resolver = name -> Optional.empty();
    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void extract_resolvesVariable() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request['key']");
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of(Collections.singletonMap("key", "value"));
      }
      return Optional.empty();
    };
    
    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("value");
  }

  @Test
  public void extract_nonStringResult_returnsNull() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request");
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of(123);
      }
      return Optional.empty();
    };
    
    String result = extractor.extract(resolver);
    assertThat(result).isNull();
  }

  @Test
  public void extract_evaluationError_throws() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request.bad");
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of("foo");
      }
      return Optional.empty();
    };
    
    try {
      extractor.extract(resolver);
      fail("Should throw CelEvaluationException");
    } catch (CelEvaluationException e) {
      // Expected
    }
  }

  @Test
  public void extract_nonStringResult_returnsDefaultValue() throws Exception {
    CelAbstractSyntaxTree ast = CelMatcherTestHelper.compileAst("request");
    CelStringExtractor extractor = CelStringExtractor.compile(ast, "default_val");
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of(123);
      }
      return java.util.Optional.empty();
    };
    
    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void extract_evaluationError_returnsDefaultValue() throws Exception {
    CelAbstractSyntaxTree ast = CelMatcherTestHelper.compileAst("request.bad");
    CelStringExtractor extractor = CelStringExtractor.compile(ast, "default_val");
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of("foo");
      }
      return java.util.Optional.empty();
    };
    
    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void extract_withCelVariableResolver_resolvesVariable() throws Exception {
    CelAbstractSyntaxTree ast = CelMatcherTestHelper.compileAst("request['key']");
    CelStringExtractor extractor = CelStringExtractor.compile(ast, "default_val");
    
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of(Collections.singletonMap("key", "value"));
      }
      return java.util.Optional.empty();
    };

    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("value");
  }

  @Test
  public void extract_withCelVariableResolver_evalError_returnsDefaultValue() throws Exception {
    CelAbstractSyntaxTree ast = CelMatcherTestHelper.compileAst("request.bad");
    CelStringExtractor extractor = CelStringExtractor.compile(ast, "default_val");
    
    CelVariableResolver resolver = name -> {
      if ("request".equals(name)) {
        return Optional.of("foo");
      }
      return java.util.Optional.empty();
    };

    String result = extractor.extract(resolver);
    assertThat(result).isEqualTo("default_val");
  }

  @Test
  public void compile_invalidSyntax_throws() {
    try {
      CelMatcherTestHelper.compileStringExtractor("invalid syntax ???");
      fail("Should throw CelValidationException");
    } catch (dev.cel.common.CelValidationException e) {
      assertThat(e).hasMessageThat().isNotEmpty();
    } catch (Exception e) {
      fail("Threw wrong exception type: " + e.getClass().getName());
    }
  }

  @Test
  public void extract_withCelVariableResolver() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'val'");
    CelVariableResolver resolver = name -> Optional.empty();

    assertThat(extractor.extract(resolver)).isEqualTo("val");
  }

  @Test
  public void extract_unsupportedInputType_throws() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'foo'");
    try {
      extractor.extract("not-a-map");
      fail("Should have thrown CelEvaluationException");
    } catch (CelEvaluationException e) {
      assertThat(e).hasMessageThat().contains("Unsupported input type");
    }
  }
}
