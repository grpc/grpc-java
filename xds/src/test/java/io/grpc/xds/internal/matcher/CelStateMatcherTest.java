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
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CelStateMatcherTest {

  private static CelCompiler COMPILER;

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

  @Test
  public void celMatcher_match() {
    CelMatcher celMatcher = createCelMatcher("request.path == '/good'");
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
    
    when(context.getPath()).thenReturn("/bad");
    result = matcher.match(context, 0);
    TypedExtensionConfig noMatchAction = result.actions.get(0);
    assertThat(noMatchAction.getName()).isEqualTo("no-match");
  }

  @Test
  public void celMatcher_throwsIfReturnsString() {
    try {
      io.grpc.xds.internal.matcher.CelMatcherTestHelper.compile("'should be bool'");
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
    when(context.getMetadata()).thenReturn(new Metadata());
    when(context.getId()).thenReturn("1");

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("no-match");
  }

  @Test
  public void celStringExtractor_throwsIfReturnsBool() {
    try {
      io.grpc.xds.internal.matcher.CelMatcherTestHelper.compileStringExtractor("true");
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
    MatchResult result = matcher.match(context, 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("no-match");
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
      assertThat(e).hasMessageThat().contains("Invalid CelMatcher config");
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
          .contains("Type mismatch");
    }
  }

  @Test
  public void celMatcher_wrongInput_throws() {
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
          .contains("Type mismatch");
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
      assertThat(e).hasMessageThat().contains("Invalid CelMatcher config");
    }
  }

  @Test
  public void celMatcher_withCelExprString_throws() {
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
      assertThat(e).hasMessageThat().contains("Invalid CelMatcher config");
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
  public void singlePredicate_invalidCelMatcherProto_throws() {
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
        
    assertThat(evaluator.evaluate(mock(MatchContext.class))).isFalse();
  }
}
