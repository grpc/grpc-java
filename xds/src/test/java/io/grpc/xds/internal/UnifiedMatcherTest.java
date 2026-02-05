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

    assertThat(matcher.match(dataMatch)).isEqualTo("action1");
    assertThat(matcher.match(dataNoMatch)).isNull();
  }
}
