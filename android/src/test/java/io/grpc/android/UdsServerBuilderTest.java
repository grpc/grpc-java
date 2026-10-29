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

package io.grpc.android;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.LocalSocketAddress.Namespace;
import io.grpc.Server;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link UdsServerBuilder}. The {@code grpc-okhttp}-absent {@link
 * UnsupportedOperationException} case is left untested here because {@code grpc-okhttp} is on the
 * test classpath and hiding it inside Robolectric's sandbox classloader is fragile.
 */
@RunWith(RobolectricTestRunner.class)
public final class UdsServerBuilderTest {

  @Test
  public void forPath_abstract_buildsServer() {
    Server server = UdsServerBuilder.forPath("com.example.service", Namespace.ABSTRACT).build();

    assertThat(server).isNotNull();
  }

  @Test
  public void forPath_filesystemNamespace_throws() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UdsServerBuilder.forPath("/data/data/app/files/s.sock", Namespace.FILESYSTEM));

    assertThat(e).hasMessageThat().contains("FILESYSTEM");
  }

  @Test
  public void forPath_reservedNamespace_throws() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UdsServerBuilder.forPath("name", Namespace.RESERVED));

    assertThat(e).hasMessageThat().contains("RESERVED");
  }

  @Test
  public void forPath_nullPath_throwsNpe() {
    assertThrows(
        NullPointerException.class, () -> UdsServerBuilder.forPath(null, Namespace.ABSTRACT));
  }

  @Test
  public void forPath_nullNamespace_throwsNpe() {
    assertThrows(NullPointerException.class, () -> UdsServerBuilder.forPath("name", null));
  }
}
