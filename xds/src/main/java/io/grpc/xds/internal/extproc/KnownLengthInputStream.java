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

package io.grpc.xds.internal.extproc;

import com.google.protobuf.ByteString;
import io.grpc.KnownLength;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} backed by a {@link ByteString} that implements {@link KnownLength}.
 */
public final class KnownLengthInputStream extends InputStream implements KnownLength {
  private final InputStream delegate;

  public KnownLengthInputStream(ByteString byteString) {
    this.delegate = byteString.newInput();
  }

  @Override
  public int read() throws IOException {
    return delegate.read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return delegate.read(b, off, len);
  }

  @Override
  public int available() throws IOException {
    return delegate.available();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
