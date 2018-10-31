/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import io.grpc.okhttp.internal.framed.Settings;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import okio.Buffer;

final class ExceptionHandlingFrameWriter implements FrameWriter {

  private static final Logger log = Logger.getLogger(OkHttpClientTransport.class.getName());
  // Some exceptions are not very useful and add too much noise to the log
  private static final Set<String> QUIET_ERRORS =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Socket closed")));

  private final TransportExceptionHandler transportExceptionHandler;

  private FrameWriter frameWriter;
  private Socket socket;

  ExceptionHandlingFrameWriter(TransportExceptionHandler transportExceptionHandler) {
    this.transportExceptionHandler =
        checkNotNull(transportExceptionHandler, "transportExceptionHandler");
  }

  /**
   * Set the real frameWriter and the corresponding underlying socket, the socket is needed for
   * closing.
   *
   * <p>should only be called by thread of executor.
   */
  void becomeConnected(FrameWriter frameWriter, Socket socket) {
    Preconditions.checkState(this.frameWriter == null,
        "ExceptionHandlingFrameWriter's becomeConnected should only be called once.");
    this.frameWriter = Preconditions.checkNotNull(frameWriter, "frameWriter");
    this.socket = Preconditions.checkNotNull(socket, "socket");
  }

  @Override
  public void connectionPreface() {
    checkInit();
    try {
      frameWriter.connectionPreface();
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  private void checkInit() {
    if (this.frameWriter == null) {
      transportExceptionHandler.onException(
          new IOException("Unable to perform write due to unavailable frameWriter."));
    }
  }

  @Override
  public void ackSettings(Settings peerSettings) {
    checkInit();
    try {
      frameWriter.ackSettings(peerSettings);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders) {
    checkInit();
    try {
      frameWriter.pushPromise(streamId, promisedStreamId, requestHeaders);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void flush() {
    checkInit();
    try {
      frameWriter.flush();
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void synStream(
      boolean outFinished,
      boolean inFinished,
      int streamId,
      int associatedStreamId,
      List<Header> headerBlock) {
    checkInit();
    try {
      frameWriter.synStream(outFinished, inFinished, streamId, associatedStreamId, headerBlock);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void synReply(boolean outFinished, int streamId,
      List<Header> headerBlock) {
    checkInit();
    try {
      frameWriter.synReply(outFinished, streamId, headerBlock);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void headers(int streamId, List<Header> headerBlock) {
    checkInit();
    try {
      frameWriter.headers(streamId, headerBlock);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void rstStream(int streamId, ErrorCode errorCode) {
    checkInit();
    try {
      frameWriter.rstStream(streamId, errorCode);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public int maxDataLength() {
    checkInit();
    return frameWriter.maxDataLength();
  }

  @Override
  public void data(boolean outFinished, int streamId, Buffer source, int byteCount) {
    checkInit();
    try {
      frameWriter.data(outFinished, streamId, source, byteCount);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void settings(Settings okHttpSettings) {
    checkInit();
    try {
      frameWriter.settings(okHttpSettings);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void ping(boolean ack, int payload1, int payload2) {
    checkInit();
    try {
      frameWriter.ping(ack, payload1, payload2);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void goAway(int lastGoodStreamId, ErrorCode errorCode,
      byte[] debugData) {
    checkInit();
    try {
      frameWriter.goAway(lastGoodStreamId, errorCode, debugData);
      // Flush it since after goAway, we are likely to close this writer.
      frameWriter.flush();
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void windowUpdate(int streamId, long windowSizeIncrement) {
    checkInit();
    try {
      frameWriter.windowUpdate(streamId, windowSizeIncrement);
    } catch (IOException e) {
      transportExceptionHandler.onException(e);
    }
  }

  @Override
  public void close() {
    if (frameWriter == null) {
      return;
    }
    try {
      frameWriter.close();
      socket.close();
    } catch (IOException e) {
      log.log(getLogLevel(e), "Failed closing connection", e);
    }
  }

  /**
   * Accepts a throwable and returns the appropriate logging level. Uninteresting exceptions
   * should not clutter the log.
   */
  @VisibleForTesting
  static Level getLogLevel(Throwable t) {
    if (t instanceof IOException
        && t.getMessage() != null
        && QUIET_ERRORS.contains(t.getMessage())) {
      return Level.FINE;
    }
    return Level.INFO;
  }

  /** A class that handles transport exception. */
  interface TransportExceptionHandler {
    /** Handles exception. */
    void onException(Throwable throwable);
  }
}
