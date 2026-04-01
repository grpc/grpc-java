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

package io.grpc.xds.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.github.xds.core.v3.TypedExtensionConfig;
import com.github.xds.type.matcher.v3.Matcher;
import io.grpc.xds.internal.UnifiedMatcher.MatchingData;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UnifiedMatcherTest {

  @Test
  public void testExactMatch() {
    TypedExtensionConfig actionConfig = TypedExtensionConfig.newBuilder()
        .setName("action1")
        .build();
    Matcher.MatcherTree tree = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setName("input1"))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("value1", Matcher.OnMatch.newBuilder()
                .setAction(actionConfig)
                .build()))
        .build();
    Matcher matcherProto = Matcher.newBuilder()
        .setMatcherTree(tree)
        .build();

    UnifiedMatcher<String> matcher = UnifiedMatcher.create(
        matcherProto, config -> config.getName());

    MatchingData dataMatch = new MatchingData() {
      @Override
      public String getRelayedInput(TypedExtensionConfig inputConfig) {
        if (inputConfig.getName().equals("input1")) {
          return "value1";
        }
        return null;
      }
    };

    MatchingData dataNoMatch = new MatchingData() {
      @Override
      public String getRelayedInput(TypedExtensionConfig inputConfig) {
        return "value2";
      }
    };

    assertThat(matcher.match(dataMatch)).containsExactly("action1");
    assertThat(matcher.match(dataNoMatch)).isNull();
  }

  @Test
  public void testKeepMatching() {
    TypedExtensionConfig actionConfig1 = TypedExtensionConfig.newBuilder()
        .setName("action1")
        .build();
    TypedExtensionConfig actionConfig2 = TypedExtensionConfig.newBuilder()
        .setName("action2")
        .build();

    Matcher.MatcherList matcherList = Matcher.MatcherList.newBuilder()
        .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
            .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                    .setInput(TypedExtensionConfig.newBuilder().setName("input1"))
                    .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                        .setExact("value1"))))
            .setOnMatch(Matcher.OnMatch.newBuilder()
                .setAction(actionConfig1)
                .setKeepMatching(true))) // keepMatching = true
        .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
            .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                    .setInput(TypedExtensionConfig.newBuilder().setName("input1"))
                    .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                        .setExact("value1"))))
            .setOnMatch(Matcher.OnMatch.newBuilder()
                .setAction(actionConfig2))) // keepMatching = false (default)
        .build();

    Matcher matcherProto = Matcher.newBuilder()
        .setMatcherList(matcherList)
        .build();

    UnifiedMatcher<String> matcher = UnifiedMatcher.create(
        matcherProto, config -> config.getName());

    MatchingData dataMatch = new MatchingData() {
      @Override
      public String getRelayedInput(TypedExtensionConfig inputConfig) {
        if (inputConfig.getName().equals("input1")) {
          return "value1";
        }
        return null;
      }
    };

    assertThat(matcher.match(dataMatch)).containsExactly("action1", "action2").inOrder();
  }

  @Test
  public void testMaxDepth16Exceeded() {
    Matcher proto = Matcher.newBuilder()
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.getDefaultInstance()))
        .build();
    for (int i = 0; i < 17; i++) {
      proto = Matcher.newBuilder()
          .setOnNoMatch(Matcher.OnMatch.newBuilder().setMatcher(proto).build())
          .build();
    }
    final Matcher finalProto = proto;
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> UnifiedMatcher.create(finalProto, config -> config.getName()));
    assertThat(e).hasMessageThat().contains("Maximum recursion depth of 16 exceeded");
  }

  @Test
  public void testCustomMatchInSinglePredicateRejected() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setCustomMatch(TypedExtensionConfig.getDefaultInstance())))))
        .build();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> UnifiedMatcher.create(proto, config -> config.getName()));
    assertThat(e).hasMessageThat().contains("custom_match is not supported in SinglePredicate");
  }

  @Test
  public void testCustomMatchInMatcherTreeRejected() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setCustomMatch(TypedExtensionConfig.getDefaultInstance()))
        .build();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> UnifiedMatcher.create(proto, config -> config.getName()));
    assertThat(e).hasMessageThat().contains("custom_match is not supported in MatcherTree");
  }

  @Test
  public void testCustomStringMatcherRejected() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherList(Matcher.MatcherList.newBuilder()
            .addMatchers(Matcher.MatcherList.FieldMatcher.newBuilder()
                .setPredicate(Matcher.MatcherList.Predicate.newBuilder()
                    .setSinglePredicate(Matcher.MatcherList.Predicate.SinglePredicate.newBuilder()
                        .setValueMatch(com.github.xds.type.matcher.v3.StringMatcher.newBuilder()
                            .setCustom(TypedExtensionConfig.getDefaultInstance()))))))
        .build();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> UnifiedMatcher.create(proto, config -> config.getName()));
    assertThat(e).hasMessageThat().contains("custom string matcher is not supported");
  }
}
