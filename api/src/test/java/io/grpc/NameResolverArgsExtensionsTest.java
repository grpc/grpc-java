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
  private static final Args.Key<String> FOO_KEY = Args.Key.create("foo");
  private static final Args.Key<String> BAR_KEY = Args.Key.create("bar");
  private static final Args.Key<String> QUX_KEY = Args.Key.create("qux");

  @Test
  public void buildExtensions() {
    Extensions exts = Extensions.newBuilder().set(FOO_KEY, "To be, or not to be?").build();
    assertSame("To be, or not to be?", exts.get(FOO_KEY));
    assertThat(exts.keysForTest()).hasSize(1);
  }

  @Test
  public void duplicates() {
    Extensions exts =
        Extensions.newBuilder()
            .set(FOO_KEY, "To be?")
            .set(FOO_KEY, "Or not to be?")
            .set(Args.Key.create("yolo"), "I'm not a duplicate")
            .build();
    assertThat(exts.get(FOO_KEY)).isEqualTo("Or not to be?");
    assertThat(exts.keysForTest()).hasSize(2);
  }

  @Test
  public void toBuilder() {
    Extensions exts =
        Extensions.newBuilder().set(FOO_KEY, "To be?").build().toBuilder()
            .set(FOO_KEY, "Or not to be?")
            .set(Args.Key.create("yolo"), "I'm not a duplicate")
            .build();
    assertThat(exts.get(FOO_KEY)).isEqualTo("Or not to be?");
    assertThat(exts.keysForTest()).hasSize(2);
  }

  @Test
  public void empty() {
    assertThat(Extensions.EMPTY.keysForTest()).isEmpty();
  }

  @Test
  public void setAll() {
    Extensions newExts =
        Extensions.newBuilder()
            .set(FOO_KEY, "foo-orig")
            .set(BAR_KEY, "bar-orig")
            .setAll(
                Extensions.newBuilder()
                    .set(FOO_KEY, "foo-updated")
                    .set(QUX_KEY, "qux-updated")
                    .build())
            .build();
    assertThat(newExts.get(FOO_KEY)).isEqualTo("foo-updated");
    assertThat(newExts.get(BAR_KEY)).isEqualTo("bar-orig");
    assertThat(newExts.get(QUX_KEY)).isEqualTo("qux-updated");
    assertThat(newExts.keysForTest()).hasSize(3);
  }
}
