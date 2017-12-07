/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc.testing;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Context;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class GrpcContextRuleTest {
  @Rule public final GrpcContextRule contextRule = new GrpcContextRule();

  private static Context.Key<String> key = Context.key("key");

  @Test
  public void badAttach() {
    Context ctx = Context.current();
    // Never do this for real
    ctx.withValue(key, "value").attach();

    ctx = Context.current();
    assertThat(key.get()).isNotEmpty();
  }

  @Test
  public void verifyEmptyContext() {
    assertThat(key.get()).isNull();
  }
}
