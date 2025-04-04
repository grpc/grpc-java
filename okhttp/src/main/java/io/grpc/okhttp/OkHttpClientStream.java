/*
 * Copyright 2014 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.ClientStreamListener.RpcProgress.PROCESSED;

import com.google.common.io.BaseEncoding;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.AbstractClientStream;
import io.grpc.internal.Http2ClientStreamTransportState;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.Header;
import io.perfmark.PerfMark;
import io.perfmark.Tag;
import io.perfmark.TaskCloseable;
import java.util.List;
import okio.Buffer;

/**
 * Client stream for the okhttp transport.
 */
class OkHttpClientStream extends AbstractClientStream {

  private static final Buffer EMPTY_BUFFER = new Buffer();

  public static final int ABSENT_ID = -1;

  private final MethodDescriptor<?, ?> method;

  private final String userAgent;
  private final StatsTraceContext statsTraceCtx;
  private String authority;
  private final TransportState state;
  private final Sink sink = new Sink();
  private final Attributes attributes;

  private boolean useGet = false;

  OkHttpClientStream(
      MethodDescriptor<?, ?> method,
      Metadata headers,
      ExceptionHandlingFrameWriter frameWriter,
      OkHttpClientTransport transport,
      OutboundFlowController outboundFlow,
      Object lock,
      int maxMessageSize,
      int initialWindowSize,
      String authority,
      String userAgent,
      StatsTraceContext statsTraceCtx,
      TransportTracer transportTracer,
      CallOptions callOptions,
      boolean useGetForSafeMethods) {
    super(
        new OkHttpWritableBufferAllocator(),
        statsTraceCtx,
        transportTracer,
        headers,
        callOptions,
        useGetForSafeMethods && method.isSafe());
    this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
    this.method = method;
    this.authority = authority;
    this.userAgent = userAgent;
    // OkHttpClientStream is only created after the transport has finished connecting,
    // so it is safe to read the transport attributes.
    // We make a copy here for convenience, even though we can ask the transport.
    this.attributes = transport.getAttributes();
    this.state =
        new TransportState(
            maxMessageSize,
            statsTraceCtx,
            lock,
            frameWriter,
            outboundFlow,
            transport,
            initialWindowSize,
            method.getFullMethodName(),
            callOptions);
  }

  @Override
  protected TransportState transportState() {
    return state;
  }

  @Override
  protected Sink abstractClientStreamSink() {
    return sink;
  }

  /**
   * Returns the type of this stream.
   */
  public MethodDescriptor.MethodType getType() {
    return method.getType();
  }

  /**
   * Returns whether the stream uses GET. This is not known until after {@link Sink#writeHeaders} is
   * invoked.
   */
  boolean useGet() {
    return useGet;
  }

  @Override
  public void setAuthority(String authority) {
    this.authority = checkNotNull(authority, "authority");
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  class Sink implements AbstractClientStream.Sink {
    @Override
    public void writeHeaders(Metadata metadata, byte[] payload) {
      try (TaskCloseable ignore = PerfMark.traceTask("OkHttpClientStream$Sink.writeHeaders")) {
        String defaultPath = "/" + method.getFullMethodName();
        if (payload != null) {
          useGet = true;
          defaultPath += "?" + BaseEncoding.base64().encode(payload);
        }
        synchronized (state.lock) {
          state.streamReady(metadata, defaultPath);
        }
      }
    }

    @Override
    public void writeFrame(
        WritableBuffer frame, boolean endOfStream, boolean flush, int numMessages) {
      try (TaskCloseable ignore = PerfMark.traceTask("OkHttpClientStream$Sink.writeFrame")) {
        Buffer buffer;
        if (frame == null) {
          buffer = EMPTY_BUFFER;
        } else {
          buffer = ((OkHttpWritableBuffer) frame).buffer();
          int size = (int) buffer.size();
          if (size > 0) {
            onSendingBytes(size);
          }
        }

        synchronized (state.lock) {
          state.sendBuffer(buffer, endOfStream, flush);
          getTransportTracer().reportMessageSent(numMessages);
        }
      }
    }

    @Override
    public void cancel(Status reason) {
      try (TaskCloseable ignore = PerfMark.traceTask("OkHttpClientStream$Sink.cancel")) {
        synchronized (state.lock) {
          state.cancel(reason, true, null);
        }
      }
    }
  }

  class TransportState extends Http2ClientStreamTransportState
      implements OutboundFlowController.Stream {
    private final int initialWindowSize;
    private final Object lock;
    @GuardedBy("lock")
    private List<Header> requestHeaders;
    @GuardedBy("lock")
    private Buffer pendingData = new Buffer();
    private boolean pendingDataHasEndOfStream = false;
    private boolean flushPendingData = false;
    @GuardedBy("lock")
    private boolean cancelSent = false;
    @GuardedBy("lock")
    private int window;
    @GuardedBy("lock")
    private int processedWindow;
    @GuardedBy("lock")
    private final ExceptionHandlingFrameWriter frameWriter;
    @GuardedBy("lock")
    private final OutboundFlowController outboundFlow;
    @GuardedBy("lock")
    private final OkHttpClientTransport transport;
    /** True iff neither {@link #cancel} nor {@link #start(int)} have been called. */
    @GuardedBy("lock")
    private boolean canStart = true;
    private final Tag tag;
    @GuardedBy("lock")
    private OutboundFlowController.StreamState outboundFlowState;
    private int id = ABSENT_ID;

    public TransportState(
        int maxMessageSize,
        StatsTraceContext statsTraceCtx,
        Object lock,
        ExceptionHandlingFrameWriter frameWriter,
        OutboundFlowController outboundFlow,
        OkHttpClientTransport transport,
        int initialWindowSize,
        String methodName,
        CallOptions options) {
      super(maxMessageSize, statsTraceCtx, OkHttpClientStream.this.getTransportTracer(), options);
      this.lock = checkNotNull(lock, "lock");
      this.frameWriter = frameWriter;
      this.outboundFlow = outboundFlow;
      this.transport = transport;
      this.window = initialWindowSize;
      this.processedWindow = initialWindowSize;
      this.initialWindowSize = initialWindowSize;
      tag = PerfMark.createTag(methodName);
    }

    @SuppressWarnings("GuardedBy")
    @GuardedBy("lock")
    public void start(int streamId) {
      checkState(id == ABSENT_ID, "the stream has been started with id %s", streamId);
      id = streamId;
      outboundFlowState = outboundFlow.createState(this, streamId);
      // TODO(b/145386688): This access should be guarded by 'OkHttpClientStream.this.state.lock';
      // instead found: 'this.lock'
      state.onStreamAllocated();

      if (canStart) {
        // Only happens when the stream has neither been started nor cancelled.
        frameWriter.synStream(useGet, false, id, 0, requestHeaders);
        statsTraceCtx.clientOutboundHeaders();
        requestHeaders = null;

        if (pendingData.size() > 0) {
          outboundFlow.data(
              pendingDataHasEndOfStream, outboundFlowState, pendingData, flushPendingData);

        }
        canStart = false;
      }
    }

    @GuardedBy("lock")
    @Override
    protected void onStreamAllocated() {
      super.onStreamAllocated();
      getTransportTracer().reportLocalStreamStarted();
    }

    @GuardedBy("lock")
    @Override
    protected void http2ProcessingFailed(Status status, boolean stopDelivery, Metadata trailers) {
      cancel(status, stopDelivery, trailers);
    }

    @Override
    @GuardedBy("lock")
    public void deframeFailed(Throwable cause) {
      http2ProcessingFailed(Status.fromThrowable(cause), true, new Metadata());
    }

    @Override
    @GuardedBy("lock")
    public void bytesRead(int processedBytes) {
      processedWindow -= processedBytes;
      if (processedWindow <= initialWindowSize * Utils.DEFAULT_WINDOW_UPDATE_RATIO) {
        int delta = initialWindowSize - processedWindow;
        window += delta;
        processedWindow += delta;
        frameWriter.windowUpdate(id(), delta);
      }
    }

    @Override
    @GuardedBy("lock")
    public void deframerClosed(boolean hasPartialMessage) {
      onEndOfStream();
      super.deframerClosed(hasPartialMessage);
    }

    @Override
    @GuardedBy("lock")
    public void runOnTransportThread(final Runnable r) {
      synchronized (lock) {
        r.run();
      }
    }

    /**
     * Must be called with holding the transport lock.
     */
    @GuardedBy("lock")
    public void transportHeadersReceived(List<Header> headers, boolean endOfStream) {
      if (endOfStream) {
        transportTrailersReceived(Utils.convertTrailers(headers));
      } else {
        transportHeadersReceived(Utils.convertHeaders(headers));
      }
    }

    /**
     * Must be called with holding the transport lock.
     */
    @GuardedBy("lock")
    public void transportDataReceived(okio.Buffer frame, boolean endOfStream, int paddingLen) {
      // We only support 16 KiB frames, and the max permitted in HTTP/2 is 16 MiB. This is verified
      // in OkHttp's Http2 deframer. In addition, this code is after the data has been read.
      int length = (int) frame.size();
      window -= length + paddingLen;
      processedWindow -= paddingLen;
      if (window < 0) {
        frameWriter.rstStream(id(), ErrorCode.FLOW_CONTROL_ERROR);
        transport.finishStream(
            id(),
            Status.INTERNAL.withDescription(
                "Received data size exceeded our receiving window size"),
            PROCESSED, false, null, null);
        return;
      }
      super.transportDataReceived(new OkHttpReadableBuffer(frame), endOfStream);
    }

    @GuardedBy("lock")
    private void onEndOfStream() {
      if (!isOutboundClosed()) {
        // If server's end-of-stream is received before client sends end-of-stream, we just send a
        // reset to server to fully close the server side stream.
        transport.finishStream(id(),null, PROCESSED, false, ErrorCode.CANCEL, null);
      } else {
        transport.finishStream(id(), null, PROCESSED, false, null, null);
      }
    }

    @SuppressWarnings("GuardedBy")
    @GuardedBy("lock")
    private void cancel(Status reason, boolean stopDelivery, Metadata trailers) {
      if (cancelSent) {
        return;
      }
      cancelSent = true;
      if (canStart) {
        // stream is pending.
        // TODO(b/145386688): This access should be guarded by 'this.transport.lock'; instead found:
        // 'this.lock'
        transport.removePendingStream(OkHttpClientStream.this);
        // release holding data, so they can be GCed or returned to pool earlier.
        requestHeaders = null;
        pendingData.clear();
        canStart = false;
        transportReportStatus(reason, true, trailers != null ? trailers : new Metadata());
      } else {
        // If pendingData is null, start must have already been called, which means synStream has
        // been called as well.
        transport.finishStream(
            id(), reason, PROCESSED, stopDelivery, ErrorCode.CANCEL, trailers);
      }
    }

    @GuardedBy("lock")
    private void sendBuffer(Buffer buffer, boolean endOfStream, boolean flush) {
      if (cancelSent) {
        return;
      }
      if (canStart) {
        // Stream is pending start, queue the data.
        int dataSize = (int) buffer.size();
        pendingData.write(buffer, dataSize);
        pendingDataHasEndOfStream |= endOfStream;
        flushPendingData |= flush;
      } else {
        checkState(id() != ABSENT_ID, "streamId should be set");
        // If buffer > frameWriter.maxDataLength() the flow-controller will ensure that it is
        // properly chunked.
        outboundFlow.data(endOfStream, outboundFlowState, buffer, flush);
      }
    }

    @SuppressWarnings("GuardedBy")
    @GuardedBy("lock")
    private void streamReady(Metadata metadata, String path) {
      requestHeaders =
          Headers.createRequestHeaders(
              metadata,
              path,
              authority,
              userAgent,
              useGet,
              transport.isUsingPlaintext());
      // TODO(b/145386688): This access should be guarded by 'this.transport.lock'; instead found:
      // 'this.lock'
      transport.streamReadyToStart(OkHttpClientStream.this, authority);
    }

    Tag tag() {
      return tag;
    }

    int id() {
      return id;
    }

    OutboundFlowController.StreamState getOutboundFlowState() {
      synchronized (lock) {
        return outboundFlowState;
      }
    }
  }
}
