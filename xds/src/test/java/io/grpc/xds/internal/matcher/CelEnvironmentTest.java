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

import com.google.common.io.BaseEncoding;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelEnvironmentTest {

  @Test
  public void headersWrapper_resolvesPseudoHeaders() {
    MatchContext context = MatchContext.newBuilder()
        .setPath("/path")
        .setMethod("POST")
        .setHost("example.com")
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    
    assertThat(headers.get(":path")).isEqualTo("/path");
    assertThat(headers.get(":method")).isEqualTo("POST");
    assertThat(headers.get(":authority")).isEqualTo("example.com");
  }

  @Test
  public void headersWrapper_resolvesStandardHeaders() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("custom-key", Metadata.ASCII_STRING_MARSHALLER), "custom-val");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    
    assertThat(headers.get("custom-key")).isEqualTo("custom-val");
    assertThat(headers.containsKey("custom-key")).isTrue();
  }

  @Test
  @SuppressWarnings("DoNotCall")
  public void headersWrapper_entrySet_unsupported() {
    MatchContext context = MatchContext.newBuilder().build();
    Map<String, String> headers = new HeadersWrapper(context);
    
    try {
      headers.entrySet();
      fail("Should throw UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      assertThat(e).hasMessageThat().contains("Should not be called");
    }
  }

  @Test
  public void celEnvironment_resolvesRequestField() {
    MatchContext context = MatchContext.newBuilder()
        .setPath("/foo")
        .build();
    
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    
    Optional<Object> result = env.find("request.path");
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("/foo");
  }

  @Test
  public void headers_caseInsensitive() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User-Agent", Metadata.ASCII_STRING_MARSHALLER), "grpc-java");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    
    Map<String, String> headers = new HeadersWrapper(context);
    
    // CEL lookup with different casing
    assertThat(headers.get("user-agent")).isEqualTo("grpc-java");
    assertThat(headers.get("USER-AGENT")).isEqualTo("grpc-java");
    assertThat(headers.containsKey("User-Agent")).isTrue();
    assertThat(headers.containsKey("user-agent")).isTrue();
  }

  @Test
  public void headers_ignoreTe() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("te", Metadata.ASCII_STRING_MARSHALLER), "trailers");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    
    // "te" should be hidden
    assertThat(headers.get("te")).isNull();
    assertThat(headers.containsKey("te")).isFalse();
    // Case insensitive check for "TE" logic too
    assertThat(headers.get("TE")).isNull();
    assertThat(headers.containsKey("TE")).isFalse();
  }

  @Test
  public void headers_hostAliasing() {
    MatchContext context = MatchContext.newBuilder()
        .setHost("example.com")
        .build();
    Map<String, String> headers = new HeadersWrapper(context);
    
    assertThat(headers.get("host")).isEqualTo("example.com");
    assertThat(headers.get("HOST")).isEqualTo("example.com");
    assertThat(headers.get(":authority")).isEqualTo("example.com");
  }

  @Test
  public void headers_binaryHeader() {
    Metadata metadata = new Metadata();
    byte[] bytes = new byte[] { 0, 1, 2, 3 };
    metadata.put(Metadata.Key.of("test-bin", Metadata.BINARY_BYTE_MARSHALLER), bytes);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    
    // Expect Base64 encoded string
    String expected = BaseEncoding.base64().encode(bytes);
    assertThat(headers.get("test-bin")).isEqualTo(expected);
    assertThat(headers.containsKey("test-bin")).isTrue();
  }

  @Test
  public void celEnvironment_disabledFeatures_throwsValidationException() {
    // String concatenation
    try {
      CelMatcherTestHelper.compileAst("'a' + 'b'");
      Assert.fail("String concatenation should be disabled");
    } catch (CelValidationException e) {
      assertThat(e).hasMessageThat().contains("found no matching overload for '_+_'");
    }

    // List concatenation
    try {
      CelMatcherTestHelper.compileAst("[1] + [2]");
      Assert.fail("List concatenation should be disabled");
    } catch (CelValidationException e) {
      assertThat(e).hasMessageThat().contains("found no matching overload for '_+_'");
    }

    // String conversion
    try {
      CelMatcherTestHelper.compileAst("string(1)");
      Assert.fail("String conversion should be disabled");
    } catch (CelValidationException e) {
      assertThat(e).hasMessageThat().contains("undeclared reference to 'string'");
    }

    // Comprehensions
    try {
      CelMatcherTestHelper.compileAst("[1, 2, 3].all(x, x > 0)");
      Assert.fail("Comprehensions should be disabled");
    } catch (CelValidationException e) {
      assertThat(e).hasMessageThat().contains("undeclared reference to 'all'");
    }
  }

  @Test
  public void celEnvironment_method_fallback() {
    MatchContext context = MatchContext.newBuilder().build();
    
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    
    Optional<Object> result = env.find("request.method");
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("POST");
  }

  @Test
  public void celEnvironment_resolvesLazyRequestMap() {
    MatchContext context = MatchContext.newBuilder().build();
    
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    
    Optional<Object> result = env.find("request");
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isInstanceOf(Map.class);
    
    Map<?, ?> map = (Map<?, ?>) result.get();
    assertThat(map.containsKey("path")).isTrue();
    assertThat(map.size()).isAtLeast(1);
    
    try {
      map.entrySet();
      fail("Should throw UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // Expected
    }
  }

  @Test
  public void celEnvironment_timeField_supportedButNull() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    
    Optional<Object> result = env.find("request.time");
    assertThat(result.isPresent()).isFalse();
    
    // But it should be present in the map key set
    Map<?, ?> requestMap = (Map<?, ?>) env.find("request").get();
    assertThat(requestMap.containsKey("time")).isTrue();
    assertThat(requestMap.get("time")).isNull();
  }

  @Test
  public void headersWrapper_size() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("k1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    metadata.put(Metadata.Key.of("k2", Metadata.ASCII_STRING_MARSHALLER), "v2");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    HeadersWrapper headers = new HeadersWrapper(context);
    
    // 2 custom headers + 3 pseudo headers (:method, :authority, :path) = 5
    assertThat(headers.size()).isEqualTo(5);
  }

  @Test
  public void celEnvironment_accessAllFields() {
    MatchContext context = MatchContext.newBuilder()
        .setHost("host")
        .setId("id")
        .setMethod("GET")
        .build();


    GrpcCelEnvironment env = new GrpcCelEnvironment(context);

    assertThat(env.find("request.host").get()).isEqualTo("host");
    assertThat(env.find("request.id").get()).isEqualTo("id");
    assertThat(env.find("request.method").get()).isEqualTo("GET");
    assertThat(env.find("request.scheme").get()).isEqualTo("");
    assertThat(env.find("request.protocol").get()).isEqualTo("");
    assertThat(env.find("request.query").get())
        .isEqualTo("");
    assertThat(env.find("request.headers").get()).isInstanceOf(HeadersWrapper.class);
  }

  @Test
  public void celEnvironment_find_unknownField() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    assertThat(env.find("request.unknown").isPresent()).isFalse();
    assertThat(env.find("other").isPresent()).isFalse();
  }

  @Test
  public void lazyRequestMap_unknownKey_returnsNull() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    Map<?, ?> map = (Map<?, ?>) env.find("request").get();
    
    assertThat(map.get("unknown")).isNull();
    assertThat(map.get(new Object())).isNull();
    assertThat(map.containsKey(new Object())).isFalse();
  }

  @Test
  public void celMatcher_match_mapInput() throws Exception {
    CelMatcher matcher = CelMatcherTestHelper.compile("request == 'bar'");
    Map<String, String> input = Collections.singletonMap("request", "bar");
    
    assertThat(matcher.match(input)).isTrue();
  }

  @Test
  public void celMatcher_match_invalidInputType_throws() throws Exception {
    CelMatcher matcher = CelMatcherTestHelper.compile("true");
    try {
      matcher.match("invalid-input");
      fail("Should throw CelEvaluationException");
    } catch (CelEvaluationException e) {
      assertThat(e).hasMessageThat().contains("Unsupported input type");
    }
  }

  @Test
  public void celMatcher_compile_nonBooleanAst_throws() throws Exception {
    try {
      CelMatcherTestHelper.compile("'not-boolean'");
      fail("Should throw IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("must evaluate to boolean");
    }
  }

  @Test
  public void celMatcher_match_runtimeNonBooleanResult_throws() throws Exception {
    // 1. We create an AST that claims to be a BOOL but returns a STRING at runtime.
    // We use a custom compiler where 'request' is defined as a BOOL.
    CelCompiler liarCompiler = 
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("request", SimpleType.BOOL)
            .build();
    CelAbstractSyntaxTree ast = liarCompiler.compile("request").getAst();
    
    // 2. This passes CelMatcher.compile() because its static result type is BOOL.
    CelMatcher matcher = CelMatcher.compile(ast);
    
    // 3. At runtime, we provide a String. The CEL engine resolves 'request' to this String.
    Map<String, Object> input = 
        Collections.singletonMap("request", "i-am-a-string");
    
    try {
      matcher.match(input);
      Assert.fail("Should have thrown CelEvaluationException");
    } catch (CelEvaluationException e) {
      assertThat(e).hasMessageThat()
          .contains("CEL expression must evaluate to boolean, got: java.lang.String");
    }
  }

  @Test
  public void headersWrapper_get_nonStringKey_returnsNull() {
    MatchContext context = MatchContext.newBuilder().build();
    Map<String, String> headers = new HeadersWrapper(context);
    assertThat(headers.get(new Object())).isNull();
  }

  @Test
  public void headersWrapper_getHeader_binary_multipleValues() {
    Metadata metadata = new Metadata();
    byte[] val1 = new byte[] { 1, 2, 3 };
    byte[] val2 = new byte[] { 4, 5, 6 };
    Metadata.Key<byte[]> key = Metadata.Key.of("bin-header-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(key, val1);
    metadata.put(key, val2);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    String expected = com.google.common.io.BaseEncoding.base64().encode(val1) + ","
        + com.google.common.io.BaseEncoding.base64().encode(val2);
    assertThat(headers.get("bin-header-bin")).isEqualTo(expected);
  }

  @Test
  public void headersWrapper_containsKey_nonStringKey_returnsFalse() {
    MatchContext context = MatchContext.newBuilder().build();
    Map<String, String> headers = new HeadersWrapper(context);
    assertThat(headers.containsKey(new Object())).isFalse();
  }

  @Test
  public void headersWrapper_containsKey_pseudoHeader_returnsTrue() {
    MatchContext context = MatchContext.newBuilder().build();
    Map<String, String> headers = new HeadersWrapper(context);
    assertThat(headers.containsKey(":method")).isTrue();
    assertThat(headers.containsKey(":path")).isTrue();
    assertThat(headers.containsKey(":authority")).isTrue();
  }

  @Test
  public void headersWrapper_keySet_containsExpectedKeys() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("custom-key", Metadata.ASCII_STRING_MARSHALLER), "val");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    Map<String, String> headers = new HeadersWrapper(context);
    Set<String> keys = headers.keySet();
    
    assertThat(keys).containsAtLeast("custom-key", ":method", ":path", ":authority");
  }

  @Test
  public void headersWrapper_getHeader_missingBinaryHeader_returnsNull() {
    MatchContext context = MatchContext.newBuilder().build();
    Map<String, String> headers = new HeadersWrapper(context);
    assertThat(headers.get("missing-bin")).isNull();
  }

  @Test
  public void celEnvironment_resolvesRefererAndUserAgent() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("referer", Metadata.ASCII_STRING_MARSHALLER), "http://example.com");
    metadata.put(Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER), "grpc-test");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);

    assertThat(env.find("request.referer").get()).isEqualTo("http://example.com");
    assertThat(env.find("request.useragent").get()).isEqualTo("grpc-test");
  }

  @Test
  public void celEnvironment_joinsMultipleHeaderValues() {
    Metadata metadata = new Metadata();
    Metadata.Key<String> key = Metadata.Key.of("referer", Metadata.ASCII_STRING_MARSHALLER);
    metadata.put(key, "v1");
    metadata.put(key, "v2");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);

    // Tests the String.join logic in getHeader
    assertThat(env.find("request.referer").get()).isEqualTo("v1,v2");
  }

  @Test
  public void celEnvironment_find_invalidFormat() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);

    assertThat(env.find("other.path").isPresent()).isFalse();
    assertThat(env.find("request.a.b").isPresent()).isFalse();
  }

  @Test
  public void lazyRequestMap_additionalMethods() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);
    Map<?, ?> map = (Map<?, ?>) env.find("request").get();

    assertThat(map.isEmpty()).isFalse();
    assertThat(map.get("time")).isNull();
  }

  @Test
  public void celEnvironment_missingHeader_returnsEmptyString() {
    MatchContext context = MatchContext.newBuilder().build();
    GrpcCelEnvironment env = new GrpcCelEnvironment(context);

    assertThat(env.find("request.referer").get()).isEqualTo("");
  }

  @Test
  public void checkAllowedVariables_unknownVariable_throws() throws Exception {
    // 1. Create a different compiler that allows a variable other than "request"
    CelCompiler otherCompiler = 
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("unknown_var", SimpleType.STRING)
            .build();
    
    // 2. Compile an expression to get an AST containing the forbidden variable
    // We use a boolean expression so it passes the AST result type check in CelMatcher.compile
    CelAbstractSyntaxTree ast = 
        otherCompiler.compile("unknown_var == 'foo'").getAst();

    // 3. Pass the AST to the gRPC CelMatcher. This bypasses the gRPC compiler 
    // but triggers the checkAllowedVariables validation.
    try {
      CelMatcher.compile(ast);
      fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("CEL expression references unknown variable: unknown_var");
    }
  }
}
