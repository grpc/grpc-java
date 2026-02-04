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
import com.github.xds.type.matcher.v3.Matcher;
import com.google.protobuf.Any;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MatcherTreeTest {

  @Test
  public void matcherTree_missingInput_throws() {
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder().build();
    try {
      new MatcherTree(proto, null, s -> true);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("MatcherTree must have input");
    }
  }

  @Test
  public void matcherTree_unsupportedCelInput_throws() {
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
            Any.pack(com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                .getDefaultInstance())))
        .build();
    try {
      new MatcherTree(proto, null, s -> true);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("HttpAttributesCelMatchInput cannot be used with MatcherTree");
    }
  }

  @Test
  public void matcherTree_emptyMaps_throws() {
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
             Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("path").build())))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.getDefaultInstance())
        .build();
    try {
      new MatcherTree(proto, null, s -> true);
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("MatcherTree exact_match_map must contain at least one entry");
    }
  }

  @Test
  public void matcherTree_maxRecursionDepth_returnsNoMatch() {
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
            Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("path").build())))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("val", Matcher.OnMatch.newBuilder()
                .setAction(TypedExtensionConfig.newBuilder().setName("action")).build()))
        .build();
    
    MatcherTree tree = new MatcherTree(proto, null, s -> true);
    MatchContext context = mock(MatchContext.class);
    MatchResult result = tree.match(context, 17);
    assertThat(result.matched).isFalse();
  }

  @Test
  public void matcherTree_nonStringInput_fallsBack() {
    
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
            Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                .setHeaderName("foo").build())))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("val", Matcher.OnMatch.newBuilder()
                .setAction(TypedExtensionConfig.newBuilder().setName("action")).build()))
        .build();
    
    MatcherTree tree = new MatcherTree(proto, 
        Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("fallback")).build(), 
        s -> true);

    MatchContext context = mock(MatchContext.class);
    
    when(context.getMetadata()).thenReturn(new io.grpc.Metadata()); // No headers
    
    MatchResult result = tree.match(context, 0);
    assertThat(result.matched).isTrue(); // onNoMatch matched
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("fallback");
  }

  @Test
  public void matcherTree_noExactMatch_fallsBackToOnNoMatch() {
    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
             Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("foo").build())))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("val", Matcher.OnMatch.newBuilder()
                .setAction(TypedExtensionConfig.newBuilder().setName("action")).build()))
        .build();
    
    Matcher.OnMatch onNoMatch = Matcher.OnMatch.newBuilder()
        .setAction(TypedExtensionConfig.newBuilder().setName("fallback"))
        .build();
    MatcherTree tree = new MatcherTree(proto, onNoMatch, s -> true);

    MatchContext context = mock(MatchContext.class);
    io.grpc.Metadata metadata = new io.grpc.Metadata();
    metadata.put(io.grpc.Metadata.Key.of("foo", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "other");
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = tree.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("fallback");
  }

  @Test
  public void matcherTree_prefixFoundButNestedFailed_returnNoMatch_notOnNoMatch() {
    Matcher.MatcherTree nestedProto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
             Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("bar").build())))
        .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("baz", Matcher.OnMatch.newBuilder()
                .setAction(TypedExtensionConfig.newBuilder().setName("n/a")).build()))
        .build();

    Matcher.MatcherTree proto = Matcher.MatcherTree.newBuilder()
        .setInput(TypedExtensionConfig.newBuilder().setTypedConfig(
             Any.pack(io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                 .setHeaderName("path").build())))
        .setPrefixMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
            .putMap("/prefix", Matcher.OnMatch.newBuilder()
                .setMatcher(Matcher.newBuilder().setMatcherTree(nestedProto))
                .build()))
        .build();
    
    Matcher.OnMatch onNoMatch = Matcher.OnMatch.newBuilder()
        .setAction(TypedExtensionConfig.newBuilder().setName("should-not-be-called"))
        .build();
        
    MatcherTree tree = new MatcherTree(proto, onNoMatch, s -> true);
    
    MatchContext context = mock(MatchContext.class);
    io.grpc.Metadata metadata = new io.grpc.Metadata();
    metadata.put(io.grpc.Metadata.Key.of("path", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
        "/prefix/foo");
    metadata.put(io.grpc.Metadata.Key.of("bar", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "wrong");
    when(context.getMetadata()).thenReturn(metadata);
    
    MatchResult result = tree.match(context, 0);
    
    assertThat(result.matched).isFalse();
    // Verify it isn't the onNoMatch action
    if (!result.actions.isEmpty()) {
      assertThat(result.actions.get(0).getName()).isNotEqualTo("should-not-be-called");
    }
  }

  @Test
  public void matcherTree_noMap_throws() {
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(Any.pack(
                      io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                          .setHeaderName("key").build()))))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("must have either exact_match_map or prefix_match_map");
    }
  }

  @Test
  public void matcherTree_customMatch_throws() {
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(Any.pack(
                      io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                          .setHeaderName("key").build())))
              .setCustomMatch(TypedExtensionConfig.newBuilder().setName("custom")))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("does not support custom_match");
    }
  }

  @Test
  public void matcherTree_exactMatch() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("x-key").build())))
            .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                .putMap("foo", Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched_foo")).build())
                .putMap("bar", Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("matched_bar")).build())))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no_match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);    
    MatchContext context = mock(MatchContext.class);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-key", Metadata.ASCII_STRING_MARSHALLER), "foo");
    when(context.getMetadata()).thenReturn(headers);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    assertThat(result.actions.get(0).getName()).isEqualTo("matched_foo");
  }

  @Test
  public void matcherTree_prefixMatch() {
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("path").build())))
            .setPrefixMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                .putMap("/api", Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("api")).build())
                .putMap("/api/v1", Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("apiv1")).build())))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("no_match")))
        .build();

    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);
    MatchContext context = mock(MatchContext.class);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("path", Metadata.ASCII_STRING_MARSHALLER), "/api/v1/users");
    when(context.getMetadata()).thenReturn(headers);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    // Longest prefix wins
    assertThat(result.actions.get(0).getName()).isEqualTo("apiv1");
  }

  @Test
  public void matcherTree_keepMatching_aggregate() {
    // MatcherTree: Input = 'path'.
    // Map: '/prefix' -> Action 'A1', KeepMatching=True
    // OnNoMatch -> Action 'A2'
    
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("path").build())))
            .setPrefixMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                .putMap("/prefix", Matcher.OnMatch.newBuilder()
                    .setAction(TypedExtensionConfig.newBuilder().setName("A1"))
                    .setKeepMatching(true)
                    .build())))
        .setOnNoMatch(Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A2")))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto);

    MatchContext context = mock(MatchContext.class);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("path", Metadata.ASCII_STRING_MARSHALLER), "/prefix/something");
    when(context.getMetadata()).thenReturn(metadata);

    MatchResult result = matcher.match(context, 0);
    assertThat(result.matched).isTrue();
    // Correct behavior per gRFC A106: onNoMatch is ONLY for when no match is found.
    // Since we found "A1", onNoMatch ("A2") should NOT be executed.
    // keepMatching=true simply means we return with matched=true and let the parent decide.
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("A1");
  }

  @Test
  public void matcherTree_keepMatching_longestPrefixFirst() {
    // Prefix /abc: A1, keep=true
    // Prefix /ab:  A2, keep=true
    // Prefix /a:   A3, keep=FALSE

    Matcher.MatcherTree.MatchMap map = Matcher.MatcherTree.MatchMap.newBuilder()
        .putMap("/abc", Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A1"))
            .setKeepMatching(true).build())
        .putMap("/ab", Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A2"))
            .setKeepMatching(true).build())
        .putMap("/a", Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("A3"))
            .setKeepMatching(false).build())
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("path").build())))
            .setPrefixMatchMap(map))
        .build();

    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(metadataWith("path", "/abc"));
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto, (t) -> true);
    MatchResult result = matcher.match(context, 0);

    assertThat(result.matched).isTrue();
    // Implementation sorts longest to shortest: /abc, /ab, /a
    // 1. /abc matches -> A1. keep=true.
    // 2. /ab matches  -> A2. keep=true.
    // 3. /a matches   -> A3. keep=false -> STOP.
    assertThat(result.actions).hasSize(3);
    assertThat(result.actions.get(0).getName()).isEqualTo("A1");
    assertThat(result.actions.get(1).getName()).isEqualTo("A2");
    assertThat(result.actions.get(2).getName()).isEqualTo("A3");
  }

  @Test
  public void matcherTree_example4_prefixMap() {
    Matcher.MatcherTree.MatchMap map = Matcher.MatcherTree.MatchMap.newBuilder()
        .putMap("grpc", Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("shorter_prefix")).build())
        .putMap("grpc.channelz", Matcher.OnMatch.newBuilder()
            .setAction(TypedExtensionConfig.newBuilder().setName("longer_prefix")).build())
        .build();
    Matcher proto = Matcher.newBuilder()
        .setMatcherTree(Matcher.MatcherTree.newBuilder()
            .setInput(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(
                    io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput.newBuilder()
                        .setHeaderName("x-user-segment").build())))
            .setPrefixMatchMap(map))
        .build();
    UnifiedMatcher matcher = UnifiedMatcher.fromProto(proto, (t) -> true);
    
    MatchContext context = mock(MatchContext.class);
    when(context.getMetadata()).thenReturn(
        metadataWith("x-user-segment", "grpc.channelz.v1.Channelz/GetTopChannels"));
    MatchResult result = matcher.match(context, 0);

    assertThat(result.matched).isTrue();
    assertThat(result.actions).hasSize(1);
    assertThat(result.actions.get(0).getName()).isEqualTo("longer_prefix");
  }

  @Test
  public void invalidInputCombination_matcherTreeWithCelInput_throws() {
    try {
      UnifiedMatcher.fromProto(Matcher.newBuilder()
          .setMatcherTree(Matcher.MatcherTree.newBuilder()
              .setInput(TypedExtensionConfig.newBuilder()
                  .setTypedConfig(Any.pack(
                      com.github.xds.type.matcher.v3.HttpAttributesCelMatchInput
                          .getDefaultInstance())))
              .setExactMatchMap(Matcher.MatcherTree.MatchMap.newBuilder()
                  .putMap("key", Matcher.OnMatch.newBuilder()
                      .setAction(TypedExtensionConfig.newBuilder().setName("action")).build())))
          .build());
      org.junit.Assert.fail("Should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat()
          .contains("HttpAttributesCelMatchInput cannot be used with MatcherTree");
    }
  }

  private Metadata metadataWith(String key, String value) {
    Metadata m = new Metadata();
    m.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return m;
  }
}
