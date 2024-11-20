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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertSame;

import io.grpc.NameResolver.Args;
import io.grpc.NameResolver.Args.Extensions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameResolver.Args.Extensions}. */
@RunWith(JUnit4.class)
public class NameResolverArgsExtensionsTest {
  private static final Args.Key<String> YOLO_KEY = Args.Key.create("yolo");

  @Test
  public void buildExtensions() {
    Extensions attrs = Extensions.newBuilder().set(YOLO_KEY, "To be, or not to be?").build();
    assertSame("To be, or not to be?", attrs.get(YOLO_KEY));
    assertThat(attrs.keysForTest()).hasSize(1);
  }

  @Test
  public void duplicates() {
    Extensions attrs =
        Extensions.newBuilder()
            .set(YOLO_KEY, "To be?")
            .set(YOLO_KEY, "Or not to be?")
            .set(Args.Key.create("yolo"), "I'm not a duplicate")
            .build();
    assertThat(attrs.get(YOLO_KEY)).isEqualTo("Or not to be?");
    assertThat(attrs.keysForTest()).hasSize(2);
  }

  @Test
  public void toBuilder() {
    Extensions attrs =
        Extensions.newBuilder().set(YOLO_KEY, "To be?").build().toBuilder()
            .set(YOLO_KEY, "Or not to be?")
            .set(Args.Key.create("yolo"), "I'm not a duplicate")
            .build();
    assertThat(attrs.get(YOLO_KEY)).isEqualTo("Or not to be?");
    assertThat(attrs.keysForTest()).hasSize(2);
  }

  @Test
  public void empty() {
    assertThat(Extensions.EMPTY.keysForTest()).isEmpty();
  }
}
