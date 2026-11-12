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
import com.google.common.testing.EqualsTester;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for {@link UdsSocketAddress}. */
@RunWith(RobolectricTestRunner.class)
public final class UdsSocketAddressTest {

  @Test
  public void forFile_absolutePath_filesystemNamespace() {
    UdsSocketAddress address = UdsSocketAddress.forFile(new File("/data/data/app/files/s.sock"));

    assertThat(address.getName()).isEqualTo("/data/data/app/files/s.sock");
    assertThat(address.getNamespace()).isEqualTo(Namespace.FILESYSTEM);
  }

  @Test
  public void forFile_relativePath_resolvedToAbsolute() {
    UdsSocketAddress address = UdsSocketAddress.forFile(new File("relative.sock"));

    assertThat(address.getName()).startsWith("/");
    assertThat(address.getName()).endsWith("/relative.sock");
    assertThat(address.getNamespace()).isEqualTo(Namespace.FILESYSTEM);
  }

  @Test
  public void forFile_null_throwsNpe() {
    assertThrows(NullPointerException.class, () -> UdsSocketAddress.forFile(null));
  }

  @Test
  public void forAbstractName_abstractNamespace() {
    UdsSocketAddress address = UdsSocketAddress.forAbstractName("com.example.service");

    assertThat(address.getName()).isEqualTo("com.example.service");
    assertThat(address.getNamespace()).isEqualTo(Namespace.ABSTRACT);
  }

  @Test
  public void forAbstractName_null_throwsNpe() {
    assertThrows(NullPointerException.class, () -> UdsSocketAddress.forAbstractName(null));
  }

  @Test
  public void equalsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(
            UdsSocketAddress.forAbstractName("a"), UdsSocketAddress.forAbstractName("a"))
        .addEqualityGroup(UdsSocketAddress.forAbstractName("b"))
        .addEqualityGroup(
            UdsSocketAddress.forFile(new File("/tmp/name")),
            UdsSocketAddress.forFile(new File("/tmp/name")))
        .addEqualityGroup(UdsSocketAddress.forAbstractName("/tmp/name"))
        .addEqualityGroup(UdsSocketAddress.forFile(new File("/tmp/other")))
        .testEquals();
  }

  @Test
  public void toString_filesystem() {
    UdsSocketAddress address = UdsSocketAddress.forFile(new File("/data/data/app/files/s.sock"));

    assertThat(address.toString()).isEqualTo("unix:/data/data/app/files/s.sock");
  }

  @Test
  public void toString_abstract() {
    UdsSocketAddress address = UdsSocketAddress.forAbstractName("com.example.service");

    assertThat(address.toString()).isEqualTo("unix-abstract:com.example.service");
  }
}
