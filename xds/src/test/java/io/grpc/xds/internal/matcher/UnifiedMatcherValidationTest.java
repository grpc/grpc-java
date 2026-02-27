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

import com.github.xds.core.v3.TypedExtensionConfig;
import com.github.xds.type.matcher.v3.Matcher;
import com.google.protobuf.Any;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UnifiedMatcherValidationTest {

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
    String supportedType = 
        "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";

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
  public void resolveInput_malformedProto_throws() {
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(com.google.protobuf.Any.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.type.matcher.v3.HttpRequestHeaderMatchInput")
            .setValue(com.google.protobuf.ByteString.copyFromUtf8("invalid-bytes"))
            .build())
        .build();
    try {
      UnifiedMatcher.resolveInput(config);
      org.junit.Assert.fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Invalid input config");
    }
  }

  @Test
  public void matchInput_headerName_invalidCharacters_throws() {
    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput proto = 
        io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("invalid$header")
        .build();
    TypedExtensionConfig config = TypedExtensionConfig.newBuilder()
        .setTypedConfig(com.google.protobuf.Any.pack(proto))
        .build();
    try {
      UnifiedMatcher.resolveInput(config);
      org.junit.Assert.fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("Invalid header name");
    }
  }
  
  @Test
  public void checkRecursionDepth_nestedInTree_throws() {
    Matcher current = Matcher.newBuilder().build();
    for (int i = 0; i < 17; i++) {
      current = Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(com.google.protobuf.Any.pack(
                      io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                      .setHeaderName("k").build())))
              .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                  .putMap("key", Matcher.OnMatch.newBuilder().setMatcher(current).build())))
          .build();
    }
    try {
      UnifiedMatcher.fromProto(current);
      org.junit.Assert.fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("exceeds limit");
    }
  }

  @Test
  public void checkRecursionDepth_nestedInOnNoMatch_throws() {
    Matcher current = Matcher.newBuilder().build();
    for (int i = 0; i < 17; i++) {
      current = Matcher.newBuilder()
          .setOnNoMatch(Matcher.OnMatch.newBuilder().setMatcher(current))
          .build();
    }
    try {
      UnifiedMatcher.fromProto(current);
      org.junit.Assert.fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("exceeds limit");
    }
  }

  @Test
  public void onMatch_empty_throws() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder())
        .build();
    
    try {
      UnifiedMatcher.fromProto(proto);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("OnMatch must have either matcher or action");
    }
  }

  @Test
  public void matcherList_empty_throws() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()) 
        .build();
    
    try {
      UnifiedMatcher.fromProto(proto);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("MatcherList must contain at least one FieldMatcher");
    }
  }

  @Test
  public void stringMatcher_emptySuffix_throws() {
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
}
