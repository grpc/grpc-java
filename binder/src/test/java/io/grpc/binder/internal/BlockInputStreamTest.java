/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BlockInputStreamTest {

  private final byte[] buff = new byte[1024];

  @Test
  public void testNoBytes() throws Exception {
    try (BlockInputStream bis = new BlockInputStream(new byte[0])) {
      assertThat(bis.read()).isEqualTo(-1);
    }
  }

  @Test
  public void testNoBlocks() throws Exception {
    try (BlockInputStream bis = new BlockInputStream(new byte[0][], 0)) {
      assertThat(bis.read()).isEqualTo(-1);
    }
  }

  @Test
  public void testSingleBlock() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1)}, 10);
    assertThat(bis.read(buff, 0, 20)).isEqualTo(10);
    assertBytes(buff, 0, 10, 1);
  }

  @Test
  public void testMultipleBlocks() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(10, 2)}, 20);
    assertThat(bis.read(buff, 0, 20)).isEqualTo(20);
    assertBytes(buff, 0, 10, 1);
    assertBytes(buff, 10, 10, 2);
  }

  @Test
  public void testMultipleBlocks_drain() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(10, 2)}, 20);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    bis.drainTo(baos);
    byte[] data = baos.toByteArray();
    assertThat(data).hasLength(20);
    assertBytes(data, 0, 10, 1);
    assertBytes(data, 10, 10, 2);
  }

  @Test
  public void testMultipleBlocksLessData() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(10, 2)}, 15);
    assertThat(bis.read(buff, 0, 20)).isEqualTo(15);
    assertBytes(buff, 0, 10, 1);
    assertBytes(buff, 10, 5, 2);
  }

  @Test
  public void testMultipleBlocksLessData_drain() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(10, 2)}, 15);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    bis.drainTo(baos);
    byte[] data = baos.toByteArray();
    assertThat(data).hasLength(15);
    assertBytes(data, 0, 10, 1);
    assertBytes(data, 10, 5, 2);
  }

  @Test
  public void testMultipleBlocksEmptyFinalBlock() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(0, 0)}, 10);

    assertThat(bis.read(buff, 0, 20)).isEqualTo(10);
    assertBytes(buff, 0, 10, 1);
    assertThat(bis.read(buff, 0, 20)).isEqualTo(-1);
    assertThat(bis.read()).isEqualTo(-1);
  }

  @Test
  public void testMultipleBlocksEmptyFinalBlock_drain() throws Exception {
    BlockInputStream bis =
        new BlockInputStream(new byte[][] {getBytes(10, 1), getBytes(0, 0)}, 10);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    bis.drainTo(baos);
    byte[] data = baos.toByteArray();
    assertThat(data).hasLength(10);
    assertBytes(data, 0, 10, 1);
  }

  private static byte[] getBytes(int size, int val) {
    byte[] res = new byte[size];
    Arrays.fill(res, 0, size, (byte) val);
    return res;
  }

  private static void assertBytes(byte[] data, int off, int len, int val) {
    for (int i = off; i < off + len; i++) {
      assertThat(data[i]).isEqualTo((byte) val);
    }
  }
}
