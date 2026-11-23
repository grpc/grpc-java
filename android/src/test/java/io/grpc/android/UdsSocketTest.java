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

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {UdsSocketTest.ShadowLocalSocket.class})
public final class UdsSocketTest {

  private UdsSocket socket;

  @Before
  public void setUp() {
    ShadowLocalSocket.outputStream = new RecordingOutputStream();
    socket = new UdsSocket(new LocalSocketAddress("test-socket"));
  }

  @Test
  public void outputStream_bulkWriteIsDelegatedInOneCall() throws IOException {
    byte[] buffer = new byte[1024];
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = (byte) i;
    }
    int offset = 7;
    int length = 1000;

    socket.getOutputStream().write(buffer, offset, length);

    RecordingOutputStream underlying = ShadowLocalSocket.outputStream;
    assertThat(underlying.singleByteWrites).isEqualTo(0);
    assertThat(underlying.bulkWrites).isEqualTo(1);
    assertThat(underlying.lastOffset).isEqualTo(offset);
    assertThat(underlying.lastLength).isEqualTo(length);
    assertThat(underlying.toByteArray())
        .isEqualTo(Arrays.copyOfRange(buffer, offset, offset + length));
  }

  @Test
  public void outputStream_wholeArrayWriteIsDelegatedInOneCall() throws IOException {
    byte[] buffer = new byte[] {1, 2, 3, 4, 5};

    socket.getOutputStream().write(buffer);

    RecordingOutputStream underlying = ShadowLocalSocket.outputStream;
    assertThat(underlying.singleByteWrites).isEqualTo(0);
    assertThat(underlying.bulkWrites).isEqualTo(1);
    assertThat(underlying.toByteArray()).isEqualTo(buffer);
  }

  @Test
  public void outputStream_singleByteWriteIsDelegated() throws IOException {
    socket.getOutputStream().write(42);

    RecordingOutputStream underlying = ShadowLocalSocket.outputStream;
    assertThat(underlying.singleByteWrites).isEqualTo(1);
    assertThat(underlying.bulkWrites).isEqualTo(0);
    assertThat(underlying.toByteArray()).isEqualTo(new byte[] {42});
  }

  /** Records how the wrapped stream is written to, so tests can tell bulk from per-byte writes. */
  private static final class RecordingOutputStream extends OutputStream {
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    int singleByteWrites;
    int bulkWrites;
    int lastOffset = -1;
    int lastLength = -1;

    @Override
    public void write(int b) {
      singleByteWrites++;
      data.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      bulkWrites++;
      lastOffset = off;
      lastLength = len;
      // Write to the backing buffer directly; super.write(b, off, len) would call write(int).
      data.write(b, off, len);
    }

    byte[] toByteArray() {
      return data.toByteArray();
    }
  }

  /** Replaces the native-backed LocalSocket streams with a recording stream. */
  @Implements(LocalSocket.class)
  public static class ShadowLocalSocket {
    static RecordingOutputStream outputStream;

    @Implementation
    protected OutputStream getOutputStream() {
      return outputStream;
    }
  }
}
