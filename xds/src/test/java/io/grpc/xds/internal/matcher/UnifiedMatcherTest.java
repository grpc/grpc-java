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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.xds.core.v3.TypedExtensionConfig;
import com.github.xds.type.matcher.v3.CelMatcher;
import com.github.xds.type.matcher.v3.Matcher;
import com.github.xds.type.v3.CelExpression;
import com.github.xds.type.v3.CelExtractString;
import com.google.protobuf.Any;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatchResult;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UnifiedMatcherTest {

  private static CelCompiler COMPILER;

  @Test
  public void verifyCelExtractStringInputNotSupported() {
    CelExtractString proto = CelExtractString.getDefaultInstance();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(proto))
        .build();
    try {
      UnifiedMatcher.resolveInput(config);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Unsupported input type");
    }
  }

  @BeforeClass
  public static void setupCompiler() {
    COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("request", SimpleType.DYN)
        .build();
  }

  private static CelMatcher createCelMatcher(String expression) {
    try {
      CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
      CelProtoAbstractSyntaxTree protoAst = CelProtoAbstractSyntaxTree.fromCelAst(ast);
      return CelMatcher.newBuilder()
          .setExprMatch(CelExpression.newBuilder()
              .setCelExprChecked(protoAst.toCheckedExpr())
              .build())
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create CelMatcher for test", e);
    }
  }

  @Test
  public void celMatcher_match() {
    // Construct CelMatcher with checked expression
    CelMatcher celMatcher = createCelMatcher("request.path == '/good'");
    // Predicate with HttpAttributesCelMatchInput
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                        .getDefaultInstance())))
            .setCustomMatch(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(celMatcher)))
            .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(predicate))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("action1")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    when(context.getPath()).thenReturn("/good");
    when(context.getMetadata()).thenReturn(new Metadata());

    when(context.getId()).thenReturn("123");
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    TypedExtensionConfig action = result.actions.get(0);
    assertThat(action.getName()).isEqualTo("action1");
    
    // Test mismatch
    when(context.getPath()).thenReturn("/bad");
    result = matcher.match(context, 0);
    TypedExtensionConfig noMatchAction = result.actions.get(0);
    assertThat(noMatchAction.getName()).isEqualTo("no-match");
  }

  @Test
  public void celMatcher_throwsIfReturnsString() {
    try {
      io.grpc.xds.internal.matcher.CelMatcher.compile("'should be bool'");
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("must evaluate to boolean");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void celMatcher_evaluationError_returnsFalse() {
    CelMatcher celMatcher = createCelMatcher("int(request.path) == 0");
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                        .getDefaultInstance())))
            .setCustomMatch(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(celMatcher)))
            .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(predicate))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    when(context.getPath()).thenReturn("not-an-int");
    // Ensure metadata access (if any by environment) checks out
    when(context.getMetadata()).thenReturn(new Metadata());
    when(context.getId()).thenReturn("1");

    MatchResult result = matcher.match(context, 0);
    // Should return false for match, so it falls through to no-match
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("no-match");
  }

  @Test
  public void celStringExtractor_throwsIfReturnsBool() {
    try {
      io.grpc.xds.internal.matcher.CelStringExtractor.compile("true");
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("must evaluate to string");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void celMatcher_headers() {
    CelMatcher celMatcher = createCelMatcher("request.headers['x-test'] == 'value'");
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                        .getDefaultInstance())))
            .setCustomMatch(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(celMatcher)))
            .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(predicate))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    
    MatchContext context = mock(MatchContext.class);
    when(context.getPath()).thenReturn("/");
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-test", Metadata.ASCII_STRING_MARSHALLER), "value");
    when(context.getMetadata()).thenReturn(headers);
    when(context.getId()).thenReturn("123");

    MatchResult result = matcher.match(context, 0);
    TypedExtensionConfig action = result.actions.get(0);
    assertThat(action.getName()).isEqualTo("matched");
  }

  @Test
  public void matcherList_keepMatching() {
    // Matcher 1: matches path '/multi', action 'action1', keep_matching = true
    // Matcher 2: matches path '/multi', action 'action2', keep_matching = false
    Matcher.MatcherList.Predicate predicate1 = Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("path").build())))
            .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                .setExact("/multi")))
        .build();
    Matcher.MatcherList.FieldMatcher matcher1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(predicate1)
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action1"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher matcher2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(predicate1) // Same predicate
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(matcher1)
            .addMatchers(matcher2))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(new Metadata());
    // Mock header "path"
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("path", Metadata.ASCII_STRING_MARSHALLER), "/multi");
    when(context.getMetadata()).thenReturn(headers);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(2);
    assertThat(result.actions.get(0).getName()).isEqualTo("action1");
    assertThat(result.actions.get(1).getName()).isEqualTo("action2");
  }

  @Test
  public void onNoMatchShouldNotExecuteWhenKeepMatchingTrueAndMatchFound() {
    Matcher.MatcherList.FieldMatcher matcher1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("path").build())))
                .setValueMatch(
                    com.github.xds.type.matcher.v3.StringMatcher.newBuilder().setExact("/test"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("matched"))
            .setKeepMatching(true))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(matcher1))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("should-not-run")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("path", Metadata.ASCII_STRING_MARSHALLER), "/test");
    when(context.getMetadata()).thenReturn(metadata);
    MatchResult result = matcher.match(context, 0);
    
    boolean bugExists = result.actions.stream()
        .anyMatch(a -> a.getName().equals("should-not-run"));
    assertThat(bugExists).isFalse();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }

  @Test
  public void actionValidation_acceptsSupportedType() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder()
                .setName("action")
                .setTypedConfig(Any.pack(
                    com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                        .getDefaultInstance()))))
        .build();
    // Type URL of HttpAttributesCelMatchInput
    String supportedType = 
        "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";

    // Should not throw
    UnifiedMatcher.fromProto(proto, (type) -> type.equals(supportedType));
  }

  @Test
  public void actionValidation_rejectsUnsupportedType() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder()
                .setName("action")
                .setTypedConfig(Any.pack(
                    com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                        .getDefaultInstance()))))
        .build();
    
    try {
      UnifiedMatcher.fromProto(proto, (type) -> false);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Unsupported action type");
      assertThat(e).hasMessageThat().contains(
          "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput");
    }
  }

  @Test
  public void recursionLimit_validation_should_fail_at_parse_time() {
    Matcher current = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("leaf")))
        .build();

    for (int i = 0; i < 20; i++) {
      Matcher wrapper = Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setCustomMatch(TypedExtensionConfig.newBuilder().setName("dummy")))) 
                  .setOnMatch(Matcher.OnMatch.newBuilder().setMatcher(current)))) 
          .build();
      current = wrapper;
    }
    
    try {
      UnifiedMatcher.fromProto(current);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for depth > 16");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("exceeds limit");
    }
  }

  private static Matcher.MatcherList.Predicate createHeaderMatchPredicate(
      String header, String value) {
    return Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName(header).build())))
            .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                .setExact(value)))
        .build();
  }

  @Test
  public void andMatcher_allTrue_matches() {
    Matcher.MatcherList.Predicate p1 = createHeaderMatchPredicate("h1", "v1");
    Matcher.MatcherList.Predicate p2 = createHeaderMatchPredicate("h2", "v2");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setAndMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                        .addPredicate(p1).addPredicate(p2)))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    metadata.put(Metadata.Key.of("h2", Metadata.ASCII_STRING_MARSHALLER), "v2");
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }

  @Test
  public void andMatcher_oneFalse_fails() {
    Matcher.MatcherList.Predicate p1 = createHeaderMatchPredicate("h1", "v1");
    Matcher.MatcherList.Predicate p2 = createHeaderMatchPredicate("h2", "v2");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setAndMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                        .addPredicate(p1).addPredicate(p2)))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    // h2 is missing
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue(); // matched by onNoMatch
    assertThat(result.actions.get(0).getName()).isEqualTo("no-match");
  }

  @Test
  public void orMatcher_oneTrue_matches() {
    Matcher.MatcherList.Predicate p1 = createHeaderMatchPredicate("h1", "v1");
    Matcher.MatcherList.Predicate p2 = createHeaderMatchPredicate("h2", "v2");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setOrMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                        .addPredicate(p1).addPredicate(p2)))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h2", Metadata.ASCII_STRING_MARSHALLER), "v2");
    // h1 is missing
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }

  @Test
  public void notMatcher_invert() {
    Matcher.MatcherList.Predicate p1 = createHeaderMatchPredicate("h1", "v1");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setNotMatcher(p1))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    // h1 is missing, so inner p1 is false. NOT(false) -> True.
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }



  @Test
  public void requestUrlPath_available() {
    CelMatcher celMatcher = createCelMatcher("request.url_path == '/path/without/query'");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(
                                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                                    .getDefaultInstance())))
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(celMatcher)))))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    
    MatchContext context = mock(MatchContext.class);
    when(context.getPath()).thenReturn("/path/without/query");
    when(context.getMetadata()).thenReturn(new Metadata());
    when(context.getId()).thenReturn("123");
    
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }

  @Test
  public void matchInput_headerName_invalidLength() {
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput proto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("")
        .build();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(proto))
        .build();
    
    // Empty invalid
    try {
      UnifiedMatcher.resolveInput(config);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("range [1, 16384)");
    }
    
    // Too long invalid
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 16384; i++) {
      sb.append("a");
    }
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput longProto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName(sb.toString())
        .build();
    TypedExtensionConfig longConfig = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(longProto))
        .build();
        
    try {
      UnifiedMatcher.resolveInput(longConfig);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("range [1, 16384)");
    }
  }

  @Test
  public void matchInput_headerName_invalidChars_throws() {
    // Uppercase not allowed in HTTP/2 validation by Metadata.Key
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput proto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("UpperCase")
        .build();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(proto))
        .build();
    
    try {
      UnifiedMatcher.resolveInput(config);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for invalid header name");
    } catch (IllegalArgumentException e) {
       // Expected
    }
  }

  @Test
  public void invalidInputCombination_stringMatcherWithCelInput_throws() {
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(
                                  com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                                      .getDefaultInstance())))
                          .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                              .setExact("any"))))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("HttpAttributesCelMatchInput cannot be used with StringMatcher");
    }
  }



  @Test
  public void matchInput_headerName_binary() {
    String headerName = "test-bin";
    byte[] binaryValue = new byte[] {1, 2, 3};
    String expectedBase64 = com.google.common.io.BaseEncoding.base64().encode(binaryValue);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER), binaryValue);
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(metadata);
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput proto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName(headerName)
        .build();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(proto))
        .build();
        
    MatchInput<MatchContext> input = UnifiedMatcher.resolveInput(config);

    assertThat(input.apply(context)).isEqualTo(expectedBase64);
  }

  @Test
  public void matchInput_headerName_te_returnsNull() {
    String headerName = "te";
    Metadata metadata = new Metadata();
    // "te" is technically ASCII.
    metadata.put(
        Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER), "trailers");
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(metadata);
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput proto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName(headerName)
        .build();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(proto))
        .build();
        
    MatchInput<MatchContext> input = UnifiedMatcher.resolveInput(config);

    assertThat(input.apply(context)).isNull();
  }





  @Test
  public void requestHeaders_equalityCheck_failsSafely() {
    // request.headers == {'a':'1','b':'2','c':'3'} requires entrySet(),
    // which throws UnsupportedOperationException
    // We want to ensure this is caught and treated as a mismatch or error, not a crash.
    // HeadersWrapper has 3 pseudo headers by default, so size is 3.
    // We match size to force entrySet check.
    CelMatcher celMatcher = createCelMatcher(
        "request.headers == {'a':'1','b':'2','c':'3'}");
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(
                                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                                    .getDefaultInstance())))
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(celMatcher)))))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(new Metadata());
    // Should NOT throw exception
    MatchResult result = matcher.match(context, 0);
    
    // Predicate should evaluate to false (due to error or mismatch),
    // so it falls through to onNoMatch. onNoMatch has an action, so matched=true
    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("no-match");
  }

  @Test
  public void noOpMatcher_delegatesToOnNoMatch() {
    // Matcher with no list and no tree -> NoOpMatcher
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("fallback")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    // Verify it is indeed NoOpMatcher
    assertThat(matcher.getClass().getSimpleName()).isEqualTo("NoOpMatcher");

    MatchContext context = mock(MatchContext.class);
    MatchResult result = matcher.match(context, 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("fallback");
  }

  @Test
  public void matcherRunner_checkMatch_returnsActions() {
    Matcher validProto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
             .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                 .setPredicate(createHeaderMatchPredicate("h1", "v1"))
                 .setOnMatch(Matcher.OnMatch.newBuilder()
                     .setAction(TypedExtensionConfig.newBuilder().setName("runner-action")))))
        .build();
        
    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h1", Metadata.ASCII_STRING_MARSHALLER), "v1");
    when(context.getMetadata()).thenReturn(metadata);
    java.util.List<TypedExtensionConfig> results = 
        io.grpc.xds.internal.matcher.MatcherRunner.checkMatch(validProto, context);
    
    assertThat(results).isNotNull();
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getName()).isEqualTo("runner-action");
  }
  
  @Test
  public void matcherRunner_checkMatch_returnsNullOnNoMatch() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
             .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                 .setPredicate(createHeaderMatchPredicate("h1", "v1"))
                 .setOnMatch(Matcher.OnMatch.newBuilder()
                     .setAction(TypedExtensionConfig.newBuilder().setName("runner-action")))))
        .build();

    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(new Metadata()); // Empty headers
    java.util.List<TypedExtensionConfig> results = 
        io.grpc.xds.internal.matcher.MatcherRunner.checkMatch(proto, context);
    
    assertThat(results).isNull();
  }

  @Test
  public void predicate_missingType_throws() {
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.getDefaultInstance();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Predicate must have one of");
    }
  }

  @Test
  public void celMatcher_missingExprMatch_throws() {
    com.github.xds.type.matcher.v3.CelMatcher celProto = 
        com.github.xds.type.matcher.v3.CelMatcher.getDefaultInstance();
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance())))
            .setCustomMatch(TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(celProto))))
        .build();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("CelMatcher must have expr_match");
    }
  }

  @Test
  public void singlePredicate_unsupportedCustomMatcher_throws() {
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance())))
            .setCustomMatch(TypedExtensionConfig.newBuilder().setTypedConfig(
                Any.pack(com.google.protobuf.Empty.getDefaultInstance()))))
        .build();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Unsupported custom_match matcher");
    }
  }

  @Test
  public void singlePredicate_missingInput_throws() {
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setValueMatch(
                com.github.xds.type.matcher.v3.StringMatcher.newBuilder().setExact("foo")))
        .build();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("SinglePredicate must have input");
    }
  }

  @Test
  public void singlePredicate_missingMatcher_throws() {
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance()))))
        .build();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains(
          "SinglePredicate must have either value_match or custom_match");
    }
  }

  @Test
  public void orMatcher_tooFewPredicates_throws() {
    Matcher.MatcherList.Predicate.PredicateList protoList = 
        Matcher.MatcherList.Predicate.PredicateList.newBuilder()
        .addPredicate(Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(
            Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder().setName("i"))
                .setValueMatch(
                    com.github.xds.type.matcher.v3.StringMatcher.newBuilder().setExact("v"))))
        .build();
    Matcher.MatcherList.Predicate proto = Matcher.MatcherList.Predicate.newBuilder()
        .setOrMatcher(protoList)
        .build();
    try {
      PredicateEvaluator.fromProto(proto);
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("OrMatcher must have at least 2 predicates");
    }
  }

  @Test
  public void andMatcher_tooFewPredicates_throws() {
    Matcher.MatcherList.Predicate.PredicateList proto = 
        Matcher.MatcherList.Predicate.PredicateList.newBuilder()
        .addPredicate(Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(
            Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder().setName("i"))
                .setValueMatch(
                    com.github.xds.type.matcher.v3.StringMatcher.newBuilder().setExact("v"))))
        .build();
    try {
      PredicateEvaluator.fromProto(
          Matcher.MatcherList.Predicate.newBuilder().setAndMatcher(proto).build());
      org.junit.Assert.fail("Should have thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("AndMatcher must have at least 2 predicates");
    }
  }

  @Test
  public void matcherList_firstMatchWins_evenIfNestedNoMatch() {
    Matcher.MatcherList.Predicate predicate = createHeaderMatchPredicate("path", "/common");
    Matcher.MatcherList.FieldMatcher matcher1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(predicate)
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setMatcher(Matcher.newBuilder())) 
        .build();
    Matcher.MatcherList.FieldMatcher matcher2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(predicate)
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("should-not-run")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(matcher1)
            .addMatchers(matcher2))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match-global")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("path", Metadata.ASCII_STRING_MARSHALLER), "/common");
    when(context.getMetadata()).thenReturn(metadata);
    MatchResult result = matcher.match(context, 0);

    if (result.matched) {
      for (TypedExtensionConfig action : result.actions) {
        assertThat(action.getName()).isNotEqualTo("should-not-run");
      }
    }
  }

  @Test
  public void matcherTree_exactMatch_shouldNotFallBackToOnNoMatch_ifKeyFound() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("x-key").build())))
            .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                .putMap("foo", Matcher.OnMatch.newBuilder()
                    .setMatcher(Matcher.newBuilder()) // Empty matcher = No Match
                    .build())))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("fallback")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("x-key", Metadata.ASCII_STRING_MARSHALLER), "foo");
    when(context.getMetadata()).thenReturn(metadata);
    MatchResult result = matcher.match(context, 0);
    
    if (result.matched) {
      for (TypedExtensionConfig action : result.actions) {
        assertThat(action.getName()).isNotEqualTo("fallback");
      }
    }
  }

  @Test
  public void stringMatcher_contains_ignoreCase() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("x-test").build())))
            .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                .setContains("bar")
                .setIgnoreCase(true))
        .build();

    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(predicate))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched")))))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("x-test", Metadata.ASCII_STRING_MARSHALLER), "FooBaR");
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched");
  }

  @Test
  public void matcherTree_emptyMap_throws() {
    // Empty exact match map
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(Any.pack(
                      io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                          .setHeaderName("key").build())))
              .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder())) // Empty map
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("exact_match_map must contain at least one entry");
    }

    // Empty prefix match map
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(Any.pack(
                      io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                          .setHeaderName("key").build())))
              .setPrefixMatchMap(Matcher.MatcherTree.MatchMap.newBuilder())) // Empty map
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("prefix_match_map must contain at least one entry");
    }
  }

  @Test
  public void stringMatcher_emptyPatterns_throws() {
    // Empty Prefix
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(
                                  io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput
                                      .newBuilder()
                                      .setHeaderName("k").build())))
                          .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                              .setPrefix(""))))
                   .setOnMatch(Matcher.OnMatch.newBuilder()
                       .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for empty prefix");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("prefix (match_pattern) must be non-empty");
    }

    // Empty Suffix
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(
                                  io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput
                                      .newBuilder()
                                      .setHeaderName("k").build())))
                          .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                              .setSuffix(""))))
                   .setOnMatch(Matcher.OnMatch.newBuilder()
                       .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for empty suffix");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("suffix (match_pattern) must be non-empty");
    }

    // Empty Contains
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(
                                  io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput
                                      .newBuilder()
                                      .setHeaderName("k").build())))
                          .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                              .setContains(""))))
                   .setOnMatch(Matcher.OnMatch.newBuilder()
                       .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for empty contains");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("contains (match_pattern) must be non-empty");
    }

    // Empty Regex
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(
                                  io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput
                                      .newBuilder()
                                      .setHeaderName("k").build())))
                          .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                              .setSafeRegex(com.github.xds.type.matcher.v3.RegexMatcher.newBuilder()
                                  .setRegex("")))))
                   .setOnMatch(Matcher.OnMatch.newBuilder()
                       .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for empty regex");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("regex (match_pattern) must be non-empty");
    }
  }

  @Test
  public void celMatcher_wrongInput_throws() {
    // Attempt to use CelMatcher with HeaderMatchInput (invalid)
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput inputProto =
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("test").build();
    com.github.xds.type.matcher.v3.CelMatcher celMatcherProto = createCelMatcher("true");

    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherList(Matcher.MatcherList.newBuilder()
              .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                  .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                      .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                          .setInput(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(inputProto)))
                          .setCustomMatch(TypedExtensionConfig.newBuilder()
                              .setTypedConfig(Any.pack(celMatcherProto))
                              .setName("cel_matcher"))))
                   .setOnMatch(Matcher.OnMatch.newBuilder()
                       .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException for incompatible input");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("CelMatcher can only be used with HttpAttributesCelMatchInput");
    }
  }

  @Test
  public void celMatcher_withoutCelExprChecked_throws() {
    com.github.xds.type.matcher.v3.CelMatcher celMatcherParsed = 
        com.github.xds.type.matcher.v3.CelMatcher.newBuilder()
        .setExprMatch(com.github.xds.type.v3.CelExpression.newBuilder()
            .setCelExprParsed(dev.cel.expr.ParsedExpr.getDefaultInstance()))
        .build();

    try {
      UnifiedMatcher.fromProto(wrapInMatcher(celMatcherParsed));
      org.junit.Assert.fail(
          "Should have thrown IllegalArgumentException for missing cel_expr_checked");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("CelMatcher must have cel_expr_checked");
    }
  }

  @Test
  public void celMatcher_withCelExprString_throws() {
    // Create a dummy ParsedExpr using the correct type for xds.type.v3.CelExpression
    dev.cel.expr.ParsedExpr parsedExpr = 
        dev.cel.expr.ParsedExpr.getDefaultInstance();
    com.github.xds.type.matcher.v3.CelMatcher celMatcherParsed = 
        com.github.xds.type.matcher.v3.CelMatcher.newBuilder()
        .setExprMatch(com.github.xds.type.v3.CelExpression.newBuilder()
            .setCelExprParsed(parsedExpr)
            .build())
        .build();
    Matcher proto = wrapInMatcher(celMatcherParsed);

    try {
      UnifiedMatcher.fromProto(proto);
      org.junit.Assert.fail(
          "Should have thrown IllegalArgumentException for using cel_expr_parsed");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("CelMatcher must have cel_expr_checked");
    }
  }

  private Matcher wrapInMatcher(com.github.xds.type.matcher.v3.CelMatcher celMatcher) {
    return Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(
                                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                                    .getDefaultInstance())))
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(celMatcher))
                            .setName("cel_matcher"))))
                 .setOnMatch(Matcher.OnMatch.newBuilder()
                     .setAction(TypedExtensionConfig.newBuilder().setName("action")))))
        .build();
  }

  @Test
  public void matcherList_keepMatching_verification() {
    // M1: matches, action A1, keep matching
    // M2: matches, action A2, stop matching
    // M3: matches, action A3 (should be ignored)
    Matcher.MatcherList.FieldMatcher m1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("h").build())))
                .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                    .setExact("val"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A1"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher m2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("h").build())))
                .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                    .setExact("val"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A2"))
            .setKeepMatching(false))
        .build();
    Matcher.MatcherList.FieldMatcher m3 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("h").build())))
                .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                    .setExact("val"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A3")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(m1)
            .addMatchers(m2)
            .addMatchers(m3))
        .build();

    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(metadataWith("h", "val"));
    io.grpc.xds.internal.matcher.UnifiedMatcher matcher = 
        io.grpc.xds.internal.matcher.UnifiedMatcher.fromProto(proto, (t) -> true);
    MatchResult result = matcher.match(context, 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(2);
    assertThat(result.actions.get(0).getName()).isEqualTo("A1");
    assertThat(result.actions.get(1).getName()).isEqualTo("A2");
  }


  
  private Metadata metadataWith(String key, String value) {
    Metadata m = new Metadata();
    m.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return m;
  }

  // Below are the 4 Unified Matcher: Evaluation Examples from gRFC A106: https://github.com/grpc/proposal/pull/520
  @Test
  public void matcherList_example1_simpleLinearMatch() {
    // Matcher 1: x-user-segment == "premium" -> "route_to_premium_cluster"
    // Matcher 2: x-user-segment prefix "standard-" -> "route_to_standard_cluster"
    // On No Match: -> "route_to_default_cluster"

    Matcher.MatcherList.FieldMatcher matcher1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("x-user-segment").build())))
                .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                    .setExact("premium"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("route_to_premium_cluster")))
        .build();
    Matcher.MatcherList.FieldMatcher matcher2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(
                        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                            .setHeaderName("x-user-segment").build())))
                .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                    .setPrefix("standard-"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("route_to_standard_cluster")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(matcher1)
            .addMatchers(matcher2))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("route_to_default_cluster")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto, (t) -> true);

    // Scenario 1: Matches second matcher (standard user)
    MatchContext context1 = mock(MatchContext.class);
    when(context1.getMetadata()).thenReturn(metadataWith("x-user-segment", "standard-user-1"));

    MatchResult result1 = matcher.match(context1, 0);
    assertThat(result1.matched).isTrue();
    assertThat(result1.actions).hasSize(1);
    assertThat(result1.actions.get(0).getName()).isEqualTo("route_to_standard_cluster");

    // Scenario 2: Matches first matcher (premium user)
    MatchContext context2 = mock(MatchContext.class);
    when(context2.getMetadata()).thenReturn(metadataWith("x-user-segment", "premium"));

    MatchResult result2 = matcher.match(context2, 0);
    assertThat(result2.matched).isTrue();
    assertThat(result2.actions).hasSize(1);
    assertThat(result2.actions.get(0).getName()).isEqualTo("route_to_premium_cluster");

    // Scenario 3: Matches neither (fallback to default) - "Request Input 2" from user
    MatchContext context3 = mock(MatchContext.class);
    when(context3.getMetadata()).thenReturn(metadataWith("x-user-segment", "guest"));

    MatchResult result3 = matcher.match(context3, 0);
    assertThat(result3.matched).isTrue(); 
    // onNoMatch logic returns matched=true when it executes successfully
    assertThat(result3.actions).hasSize(1);
    assertThat(result3.actions.get(0).getName()).isEqualTo("route_to_default_cluster");
  }

  @Test
  public void matcherList_example2_keepMatching() {
    TypedExtensionConfig celInput = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(
            com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance()))
        .build();
    Matcher.MatcherList.FieldMatcher m1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(celInput)
                .setCustomMatch(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(createCelMatcher("true"))).setName("match1"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action_1"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher m2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(celInput)
                .setCustomMatch(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(createCelMatcher("false"))).setName("match2"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action_2")))
        .build();
    Matcher.MatcherList.FieldMatcher m3 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(celInput)
                .setCustomMatch(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(createCelMatcher("true"))).setName("match3"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action_3")))
        .build();
    Matcher.MatcherList.FieldMatcher m4 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
            .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                .setInput(celInput)
                .setCustomMatch(TypedExtensionConfig.newBuilder()
                    .setTypedConfig(Any.pack(createCelMatcher("false"))).setName("match4"))))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action_4")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(m1)
            .addMatchers(m2)
            .addMatchers(m3)
            .addMatchers(m4))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto, (t) -> true);
    MatchResult result = matcher.match(mock(MatchContext.class), 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(2);
    assertThat(result.actions.get(0).getName()).isEqualTo("action_1");
    assertThat(result.actions.get(1).getName()).isEqualTo("action_3");
  }

  @Test
  public void matcherList_example3_nestedMatcher() {
    TypedExtensionConfig celInput = TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(
            com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance()))
        .build();

    // Inner Matcher 1: False -> inner_matcher_1
    // Inner Matcher 2: True -> inner_matcher_2
    Matcher innerProto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(celInput)
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(createCelMatcher("false"))))))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("inner_matcher_1"))))
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(celInput)
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(createCelMatcher("true"))))))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("inner_matcher_2")))))
        .build();
    // Outer Matcher: True -> Nested Matcher
    Matcher outerProto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setInput(celInput)
                        .setCustomMatch(TypedExtensionConfig.newBuilder()
                            .setTypedConfig(Any.pack(createCelMatcher("true"))))))
                .setOnMatch(Matcher.OnMatch.newBuilder()
                    .setMatcher(innerProto))))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(outerProto, (t) -> true);
    MatchResult result = matcher.match(mock(MatchContext.class), 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("inner_matcher_2");
  }

  @Test
  public void singlePredicate_invalidCelMatcherProto_throws() {
    // Triggers "Invalid CelMatcher config"
    // Create a CEL matcher config with invalid bytes to trigger InvalidProtocolBufferException
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(com.google.protobuf.Any.newBuilder()
            .setTypeUrl("type.googleapis.com/xds.type.matcher.v3.CelMatcher")
            .setValue(com.google.protobuf.ByteString.copyFromUtf8("invalid"))
            .build())
        .build();

    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance())))
        .setCustomMatch(config)
        .build();
    
    try {
      PredicateEvaluator.fromProto(
          Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Invalid CelMatcher config");
    }
  }

  @Test
  public void singlePredicate_celEvalError_returnsFalse() {
    // We rely on a runtime failure (division by zero) to trigger CelEvaluationException.
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput.getDefaultInstance())))
        .setCustomMatch(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(createCelMatcher("1/0 == 0"))))
        .build();
        
    PredicateEvaluator evaluator = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
        
    // Eval should return false (caught exception)
    assertThat(evaluator.evaluate(mock(MatchContext.class))).isFalse();
  }

  @Test
  public void stringMatcher_emptySuffix_throws() {
    // Triggers "StringMatcher suffix ... must be non-empty"
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("host").build())))
        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder().setSuffix(""))
        .build();
        
    try {
      PredicateEvaluator.fromProto(
          Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains(
          "StringMatcher suffix (match_pattern) must be non-empty");
    }
  }

  @Test
  public void stringMatcher_unknownPattern_throws() {
    // Triggers "Unknown StringMatcher match pattern"
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("host").build())))
        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.getDefaultInstance())
        .build();
        
    try {
      PredicateEvaluator.fromProto(
          Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Unknown StringMatcher match pattern");
    }
  }


  @Test
  public void singlePredicate_stringMatcher_safeRegex_matches() {
    // Verifies valid safe_regex config
    com.github.xds.type.matcher.v3.RegexMatcher regexMatcher = 
        com.github.xds.type.matcher.v3.RegexMatcher.newBuilder()
        .setRegex("f.*o")
        .build();
        
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("host").build())))
        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
            .setSafeRegex(regexMatcher))
        .build();

    PredicateEvaluator evaluator = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(new Metadata());
    // host header not present -> null -> false
    assertThat(evaluator.evaluate(context)).isFalse();
    
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("host", Metadata.ASCII_STRING_MARSHALLER), "foo");
    when(context.getMetadata()).thenReturn(headers);
    assertThat(evaluator.evaluate(context)).isTrue();
  }

  @Test
  public void singlePredicate_stringMatcher_suffix_matches() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("host").build())))
        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
            .setSuffix("bar"))
        .build();

    PredicateEvaluator evaluator = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    // "foobar" ends with "bar" -> true
    assertThat(evaluator.evaluate(mockContextWith("host", "foobar"))).isTrue();
    // "foobaz" does not end with "bar" -> false
    assertThat(evaluator.evaluate(mockContextWith("host", "foobaz"))).isFalse();
  }

  @Test
  public void singlePredicate_stringMatcher_prefix_matches() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("host").build())))
        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
            .setPrefix("foo"))
        .build();

    PredicateEvaluator evaluator = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    // "foobar" starts with "foo" -> true
    assertThat(evaluator.evaluate(mockContextWith("host", "foobar"))).isTrue();
    // "barfoo" does not start with "foo" -> false
    assertThat(evaluator.evaluate(mockContextWith("host", "barfoo"))).isFalse();
  }
}
