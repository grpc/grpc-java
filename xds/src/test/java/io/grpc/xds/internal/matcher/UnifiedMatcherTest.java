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
import com.github.xds.type.matcher.v3.RegexMatcher;
import com.github.xds.type.matcher.v3.StringMatcher;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UnifiedMatcherTest {

  @Test
  public void matcherList_firstMatchWins_evenIfNestedNoMatch() {
    // matcher1: matches -> nested "no-match" (matched=false)
    // matcher2: matches -> action "action2"
    // Expect: matcher1 returns matched=false, so we proceed to matcher2, which returns action2.
    
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setMatcher(Matcher.newBuilder())) // nested matcher that doesn't match
        .build();
    Matcher.MatcherList.FieldMatcher fm2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(fm1)
            .addMatchers(fm2))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isFalse();
  }

  @Test
  public void matcherTree_exactMatch_shouldNotFallBackToOnNoMatch_ifKeyFound() {
    Matcher nestedNoMatch = Matcher.newBuilder()
        .build();

    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("key").build())))
            .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                .putMap("found", Matcher.OnMatch.newBuilder().setMatcher(nestedNoMatch).build())))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("tree-no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("key", "found"))
        .build();

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isFalse();
    assertThat(result.action).isNull();
    assertThat(result.keepMatchingActions).isEmpty();
  }

  @Test
  public void stringMatcher_contains_ignoreCase() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("key").build())))
        .setValueMatch(StringMatcher.newBuilder()
            .setContains("WoRlD")
            .setIgnoreCase(true))
        .build();

    PredicateEvaluator evaluator = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("key", "hello world"))
        .build();
    
    assertThat(evaluator.evaluate(context)).isTrue();
  }

  @Test
  public void andMatcher_allTrue_matches() {
    Matcher.MatcherList.Predicate h1 = createHeaderMatchPredicate("h", "v");
    Matcher.MatcherList.Predicate h2 = createHeaderMatchPredicate("h", "v");
    
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder()
            .setAndMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                .addPredicate(h1).addPredicate(h2)).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();
  }

  @Test
  public void andMatcher_oneFalse_fails() {
    Matcher.MatcherList.Predicate h1 = createHeaderMatchPredicate("h", "v");
    Matcher.MatcherList.Predicate h2 = createHeaderMatchPredicate("h", "x");
    
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder()
            .setAndMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                .addPredicate(h1).addPredicate(h2)).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    assertThat(eval.evaluate(context)).isFalse();
  }

  @Test
  public void orMatcher_oneTrue_matches() {
    Matcher.MatcherList.Predicate h1 = createHeaderMatchPredicate("h", "x"); // fail
    Matcher.MatcherList.Predicate h2 = createHeaderMatchPredicate("h", "v"); // match
    
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder()
            .setOrMatcher(Matcher.MatcherList.Predicate.PredicateList.newBuilder()
                .addPredicate(h1).addPredicate(h2)).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();
  }

  @Test
  public void notMatcher_invert() {
    Matcher.MatcherList.Predicate h1 = createHeaderMatchPredicate("h", "v");
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder()
            .setNotMatcher(h1).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    assertThat(eval.evaluate(context)).isFalse();

    context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "x"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();
  }

  @Test
  public void matchInput_headerName_binary() {
    String headerName = "test-bin";
    byte[] bytes = new byte[] {1, 2, 3};
    String expected = BaseEncoding.base64().encode(bytes);
    
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER), bytes);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    
    HttpRequestHeaderMatchInput proto = 
        HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName(headerName).build();
    MatchInput input = UnifiedMatcher.resolveInput(
        TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(proto)).build());
    
    assertThat(input.apply(context)).isEqualTo(expected);
  }

  @Test
  public void matchInput_headerName_binary_aggregation() {
    String headerName = "test-bin";
    byte[] v1 = new byte[] {1, 2, 3};
    byte[] v2 = new byte[] {4, 5, 6};
    String expected = BaseEncoding.base64().encode(v1) + "," 
        + BaseEncoding.base64().encode(v2);
    
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER), v1);
    metadata.put(Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER), v2);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    
    HttpRequestHeaderMatchInput proto = 
        HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName(headerName).build();
    MatchInput input = UnifiedMatcher.resolveInput(
        TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(proto)).build());
    
    assertThat(input.apply(context)).isEqualTo(expected);
  }

  @Test
  public void matchInput_headerName_binary_missing() {
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(new Metadata())
        .build();
    
    HttpRequestHeaderMatchInput proto = 
        HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("missing-bin").build();
    MatchInput input = UnifiedMatcher.resolveInput(
        TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(proto)).build());
    
    assertThat(input.apply(context)).isNull();
  }

  @Test
  public void matchInput_headerName_te_returnsNull() {
    String headerName = "te";
    Metadata metadata = new Metadata();
    // "te" is technically ASCII.
    metadata.put(
        Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER), "trailers");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    HttpRequestHeaderMatchInput proto = 
        HttpRequestHeaderMatchInput.newBuilder()
        .setHeaderName("te").build();
    MatchInput input = UnifiedMatcher.resolveInput(
        TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(proto)).build());
    
    assertThat(input.apply(context)).isNull();
  }

  @Test
  public void noOpMatcher_delegatesToOnNoMatch() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match-action")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcher.match(MatchContext.newBuilder().build(), 0);
    
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("no-match-action");
    assertThat(result.keepMatchingActions).isEmpty();
  }

  @Test
  public void matcherRunner_checkMatch_returnsActions() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action")))
        .build();
    List<TypedExtensionConfig> actions = 
        MatcherRunner.checkMatch(proto, MatchContext.newBuilder().build());
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getName()).isEqualTo("action");
  }

  @Test
  public void matcherRunner_checkMatch_returnsNullOnNoMatch() {
    Matcher proto = Matcher.newBuilder()
        .build();
    List<TypedExtensionConfig> actions = 
        MatcherRunner.checkMatch(proto, MatchContext.newBuilder().build());
    assertThat(actions).isNull();
  }

  @Test
  public void singlePredicate_stringMatcher_safeRegex_matches() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
             .setTypedConfig(Any.pack(
                 HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("k").build())))
        .setValueMatch(StringMatcher.newBuilder()
            .setSafeRegex(RegexMatcher.newBuilder()
                .setRegex("v.*"))) // v followed by anything
        .build();
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("k", "val"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();

    context = MatchContext.newBuilder()
        .setMetadata(metadataWith("k", "xal"))
        .build();
    assertThat(eval.evaluate(context)).isFalse();
  }

  @Test
  public void singlePredicate_stringMatcher_suffix_matches() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
             .setTypedConfig(Any.pack(
                 HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("k").build())))
        .setValueMatch(StringMatcher.newBuilder()
            .setSuffix("tail"))
        .build();
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("k", "detail"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();

    context = MatchContext.newBuilder()
        .setMetadata(metadataWith("k", "detai"))
        .build();
    assertThat(eval.evaluate(context)).isFalse();
  }

  @Test
  public void singlePredicate_stringMatcher_prefix_matches() {
    Matcher.MatcherList.Predicate.SinglePredicate predicate = 
        Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder()
             .setTypedConfig(Any.pack(
                 HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("k").build())))
        .setValueMatch(StringMatcher.newBuilder()
            .setPrefix("pre"))
        .build();
    PredicateEvaluator eval = PredicateEvaluator.fromProto(
        Matcher.MatcherList.Predicate.newBuilder().setSinglePredicate(predicate).build());
    
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("k", "prefix"))
        .build();
    assertThat(eval.evaluate(context)).isTrue();
  }

  @Test
  public void matcherList_keepMatching() {
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h1", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action1"))
            .setKeepMatching(true))
        .build();

    Matcher.MatcherList.FieldMatcher fm2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h2", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))
        .build();

    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(fm1)
            .addMatchers(fm2))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h1", Metadata.ASCII_STRING_MARSHALLER), "v");
    metadata.put(Metadata.Key.of("h2", Metadata.ASCII_STRING_MARSHALLER), "v");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("action2");
    assertThat(result.keepMatchingActions).hasSize(1);
    assertThat(result.keepMatchingActions.get(0).getName()).isEqualTo("action1");
  }

  @Test
  public void matcherList_keepMatching_fallsThroughToOnNoMatch() {
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h1", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action1"))
            .setKeepMatching(true))
        .build();

    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(fm1)) 
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no-match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h1", "v"))
        .build();

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    // onNoMatch IS executed because m1 had keepMatching=true and we reached end of list
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("no-match");
    assertThat(result.keepMatchingActions).hasSize(1);
    assertThat(result.keepMatchingActions.get(0).getName()).isEqualTo("action1");
  }

  @Test
  public void matcherList_example1_simpleLinearMatch() {
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h1", "v")) // No match
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action1")))
        .build();
    Matcher.MatcherList.FieldMatcher fm2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h2", "v")) // Match
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder().addMatchers(fm1).addMatchers(fm2))
        .build();
        
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h2", "v"))
        .build();
    
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("action2");
    assertThat(result.keepMatchingActions).isEmpty();
  }

  @Test
  public void matcherList_example2_keepMatching() {
    // M1 matches, keep_matching=true -> action1
    // M2 matches -> action2
    // Result: [action1, action2]
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action1"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher fm2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder().addMatchers(fm1).addMatchers(fm2))
        .build();
        
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();
    
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("action2");
    assertThat(result.keepMatchingActions).hasSize(1);
    assertThat(result.keepMatchingActions.get(0).getName()).isEqualTo("action1");
  }

  @Test
  public void matcherList_example3_nestedMatcher() {
    // M1 matches -> nested M2
    // M2 matches -> action2
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h1", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setMatcher(Matcher.newBuilder()
                .setMatcherList(Matcher.MatcherList.newBuilder()
                    .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                        .setPredicate(createHeaderMatchPredicate("h2", "v"))
                        .setOnMatch(Matcher.OnMatch.newBuilder()
                            .setAction(TypedExtensionConfig.newBuilder().setName("action2")))))))
        .build();
    
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder().addMatchers(fm1))
        .build();
    
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("h1", Metadata.ASCII_STRING_MARSHALLER), "v");
    metadata.put(Metadata.Key.of("h2", Metadata.ASCII_STRING_MARSHALLER), "v");
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadata)
        .build();
    
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("action2");
    assertThat(result.keepMatchingActions).isEmpty();
  }
  
  @Test
  public void noOpMatcher_runtimeRecursionLimit_returnsNoMatch() {
    Matcher proto = Matcher.getDefaultInstance();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    
    // Manually calling with depth > 16
    MatchResult result = matcher.match(MatchContext.newBuilder().build(), 17);
    assertThat(result.matched).isFalse();
  }

  @Test
  public void matcherList_maxRecursionDepth_returnsNoMatch() {
    // We construct a valid MatcherList but call it with a depth value that exceeds the limit.
    Matcher.MatcherList.FieldMatcher matcher = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("action")))
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder().addMatchers(matcher))
        .build();
    
    UnifiedMatcher matcherList = UnifiedMatcher.fromProto(proto);
    MatchResult result = matcherList.match(MatchContext.newBuilder().build(), 17);
    assertThat(result.matched).isFalse();
  }

  @Test
  public void matcherList_keepMatching_verification() {
    // Verifying gRFC logic:
    // If a matcher sets keep_matching=true, we add its action and continue.
    // If subsequent matchers also match, we add their actions too.
    
    Matcher.MatcherList.FieldMatcher fm1 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("a1"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher fm2 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("a2"))
            .setKeepMatching(true))
        .build();
    Matcher.MatcherList.FieldMatcher fm3 = Matcher.MatcherList.FieldMatcher.newBuilder()
        .setPredicate(createHeaderMatchPredicate("h", "v"))
        .setOnMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("a3"))) // stops here
        .build();
        
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(fm1).addMatchers(fm2).addMatchers(fm3))
        .build();
        
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = MatchContext.newBuilder()
        .setMetadata(metadataWith("h", "v"))
        .build();

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.action).isNotNull();
    assertThat(result.action.getName()).isEqualTo("a3");
    
    assertThat(result.keepMatchingActions).hasSize(2);
    assertThat(result.keepMatchingActions.get(0).getName()).isEqualTo("a1");
    assertThat(result.keepMatchingActions.get(1).getName()).isEqualTo("a2");
  }

  private static Matcher.MatcherList.Predicate createHeaderMatchPredicate(
      String name, String value) {
    return Matcher.MatcherList.Predicate.newBuilder()
        .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName(name).build())))
            .setValueMatch(StringMatcher.newBuilder()
                .setExact(value)))
        .build();
  }

  private static Metadata metadataWith(String key, String value) {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return metadata;
  }
}
