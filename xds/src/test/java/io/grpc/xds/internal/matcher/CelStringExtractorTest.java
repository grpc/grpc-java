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

import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelStringExtractorTest {

  @Test
  public void extract_simpleString() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'foo'");
    String result = extractor.extract(Collections.emptyMap());
    assertThat(result).isEqualTo("foo");
  }

  @Test
  public void extract_fromMap() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request['key']");
    Map<String, String> input = Collections.singletonMap("key", "value");
    Map<String, Object> activation = Collections.singletonMap("request", input);
    
    String result = extractor.extract(activation);
    assertThat(result).isEqualTo("value");
  }

  @Test
  public void extract_nonStringResult_returnsNull() throws Exception {
    // Expression returns DYN (compile time), but Integer at runtime
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request");
    // "request" is an integer
    Map<String, Object> activation = Collections.singletonMap("request", 123);
    
    String result = extractor.extract(activation);
    // Since 123 is not a String, it returns null
    assertThat(result).isNull();
  }

  @Test
  public void extract_evaluationError_throws() throws Exception {
    // "request.bad" on a string -> Runtime error (no such field/property)
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("request.bad");
    
    try {
      extractor.extract(Collections.singletonMap("request", "foo"));
      fail("Should throw CelEvaluationException");
    } catch (dev.cel.runtime.CelEvaluationException e) {
      // Expected
    }
  }

  @Test
  public void compile_invalidSyntax_throws() {
    try {
      CelMatcherTestHelper.compileStringExtractor("invalid syntax ???");
      fail("Should throw CelValidationException");
    } catch (Exception e) {
      // Expected (CelValidationException or similar)
    }
  }

  @Test
  public void extract_withCelVariableResolver() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'val'");
    dev.cel.runtime.CelVariableResolver resolver = name -> java.util.Optional.empty();

    assertThat(extractor.extract(resolver)).isEqualTo("val");
  }

  @Test
  public void extract_unsupportedInputType_throws() throws Exception {
    CelStringExtractor extractor = CelMatcherTestHelper.compileStringExtractor("'foo'");
    try {
      extractor.extract("not-a-map");
      fail("Should have thrown CelEvaluationException");
    } catch (dev.cel.runtime.CelEvaluationException e) {
      assertThat(e).hasMessageThat().contains("Unsupported input type");
    }
  }
}
