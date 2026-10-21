/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.okhttp;

import com.google.common.base.Preconditions;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import io.grpc.okhttp.internal.framed.Settings;
import java.io.IOException;
import java.util.List;
import okio.Buffer;


/** FrameWriter that forwards all calls to a delegate. */
abstract class ForwardingFrameWriter implements FrameWriter {
  private final FrameWriter delegate;

  public ForwardingFrameWriter(FrameWriter delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public void connectionPreface() throws IOException {
    delegate.connectionPreface();
  }

  @Override
  public void ackSettings(Settings peerSettings) throws IOException {
    delegate.ackSettings(peerSettings);
  }

  @Override
  public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
      throws IOException {
    delegate.pushPromise(streamId, promisedStreamId, requestHeaders);
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  @Override
  public void synStream(boolean outFinished, boolean inFinished, int streamId,
      int associatedStreamId, List<Header> headerBlock) throws IOException {
    delegate.synStream(outFinished, inFinished, streamId, associatedStreamId, headerBlock);
  }

  @Override
  public void synReply(boolean outFinished, int streamId, List<Header> headerBlock)
      throws IOException {
    delegate.synReply(outFinished, streamId, headerBlock);
  }

  @Override
  public void headers(int streamId, List<Header> headerBlock) throws IOException {
    delegate.headers(streamId, headerBlock);
  }

  @Override
  public void rstStream(int streamId, ErrorCode errorCode) throws IOException {
    delegate.rstStream(streamId, errorCode);
  }

  @Override
  public int maxDataLength() {
    return delegate.maxDataLength();
  }

  @Override
  public void data(boolean outFinished, int streamId, Buffer source, int byteCount)
      throws IOException {
    delegate.data(outFinished, streamId, source, byteCount);
  }

  @Override
  public void settings(Settings okHttpSettings) throws IOException {
    delegate.settings(okHttpSettings);
  }

  @Override
  public void ping(boolean ack, int payload1, int payload2) throws IOException {
    delegate.ping(ack, payload1, payload2);
  }

  @Override
  public void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData)
      throws IOException {
    delegate.goAway(lastGoodStreamId, errorCode, debugData);
  }

  @Override
  public void windowUpdate(int streamId, long windowSizeIncrement) throws IOException {
    delegate.windowUpdate(streamId, windowSizeIncrement);
  }
}
