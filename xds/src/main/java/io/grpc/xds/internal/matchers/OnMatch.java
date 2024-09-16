/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal.matchers;


import com.google.auto.value.AutoOneOf;

/** Unified Matcher API: xds.type.matcher.v3.Matcher.OnMatch. */
@AutoOneOf(OnMatch.Kind.class)
public abstract class OnMatch<InputT, ResultT> {
  public enum Kind { MATCHER, ACTION }

  public abstract Kind getKind();

  public abstract Matcher<InputT, ResultT> matcher();

  public abstract ResultT action();

  public static <InputT, ResultT> OnMatch<InputT, ResultT> ofMatcher(
      Matcher<InputT, ResultT> matcher) {
    return AutoOneOf_OnMatch.matcher(matcher);
  }

  public static <InputT, ResultT> OnMatch<InputT, ResultT> ofAction(ResultT result) {
    return AutoOneOf_OnMatch.action(result);
  }
}
