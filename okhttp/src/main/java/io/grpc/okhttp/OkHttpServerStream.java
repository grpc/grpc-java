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
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.Header;
import io.perfmark.PerfMark;
import io.perfmark.Tag;
import io.perfmark.TaskCloseable;
import java.util.List;
import javax.annotation.concurrent.GuardedBy;
import okio.Buffer;

/**
 * Server stream for the okhttp transport.
 */
class OkHttpServerStream extends AbstractServerStream {
  private final String authority;
  private final TransportState state;
  private final Sink sink = new Sink();
  private final TransportTracer transportTracer;
  private final Attributes attributes;

  public OkHttpServerStream(
      TransportState state,
      Attributes transportAttrs,
      String authority,
      StatsTraceContext statsTraceCtx,
      TransportTracer transportTracer) {
    super(new OkHttpWritableBufferAllocator(), statsTraceCtx);
    this.state = Preconditions.checkNotNull(state, "state");
    this.attributes = Preconditions.checkNotNull(transportAttrs, "transportAttrs");
    this.authority = authority;
    this.transportTracer = Preconditions.checkNotNull(transportTracer, "transportTracer");
  }

  @Override
  protected TransportState transportState() {
    return state;
  }

  @Override
  protected Sink abstractServerStreamSink() {
    return sink;
  }

  @Override
  public int streamId() {
    return state.streamId;
  }

  @Override
  public String getAuthority() {
    return authority;
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  class Sink implements AbstractServerStream.Sink {
    @Override
    public void writeHeaders(Metadata metadata) {
      try (TaskCloseable ignore =
               PerfMark.traceTask("OkHttpServerStream$Sink.writeHeaders")) {
        List<Header> responseHeaders = Headers.createResponseHeaders(metadata);
        synchronized (state.lock) {
          state.sendHeaders(responseHeaders);
        }
      }
    }

    @Override
    public void writeFrame(WritableBuffer frame, boolean flush, int numMessages) {
      try (TaskCloseable ignore =
               PerfMark.traceTask("OkHttpServerStream$Sink.writeFrame")) {
        Buffer buffer = ((OkHttpWritableBuffer) frame).buffer();
        int size = (int) buffer.size();
        if (size > 0) {
          onSendingBytes(size);
        }
        synchronized (state.lock) {
          state.sendBuffer(buffer, flush);
          transportTracer.reportMessageSent(numMessages);
        }
      }
    }

    @Override
    public void writeTrailers(Metadata trailers, boolean headersSent, Status status) {
      try (TaskCloseable ignore =
               PerfMark.traceTask("OkHttpServerStream$Sink.writeTrailers")) {
        List<Header> responseTrailers = Headers.createResponseTrailers(trailers, headersSent);
        synchronized (state.lock) {
          state.sendTrailers(responseTrailers);
        }
      }
    }

    @Override
    public void cancel(Status reason) {
      try (TaskCloseable ignore =
               PerfMark.traceTask("OkHttpServerStream$Sink.cancel")) {
        synchronized (state.lock) {
          state.cancel(ErrorCode.CANCEL, reason);
        }
      }
    }
  }

  static class TransportState extends AbstractServerStream.TransportState
      implements OutboundFlowController.Stream, OkHttpServerTransport.StreamState {
    @GuardedBy("lock")
    private final OkHttpServerTransport transport;
    private final int streamId;
    private final int initialWindowSize;
    private final Object lock;
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
    private boolean receivedEndOfStream;
    private final Tag tag;
    private final OutboundFlowController.StreamState outboundFlowState;

    public TransportState(
        OkHttpServerTransport transport,
        int streamId,
        int maxMessageSize,
        StatsTraceContext statsTraceCtx,
        Object lock,
        ExceptionHandlingFrameWriter frameWriter,
        OutboundFlowController outboundFlow,
        int initialWindowSize,
        TransportTracer transportTracer,
        String methodName) {
      super(maxMessageSize, statsTraceCtx, transportTracer);
      this.transport = Preconditions.checkNotNull(transport, "transport");
      this.streamId = streamId;
      this.lock = Preconditions.checkNotNull(lock, "lock");
      this.frameWriter = frameWriter;
      this.outboundFlow = outboundFlow;
      this.window = initialWindowSize;
      this.processedWindow = initialWindowSize;
      this.initialWindowSize = initialWindowSize;
      tag = PerfMark.createTag(methodName);
      outboundFlowState = outboundFlow.createState(this, streamId);
    }

    @Override
    @GuardedBy("lock")
    public void deframeFailed(Throwable cause) {
      cancel(ErrorCode.INTERNAL_ERROR, Status.fromThrowable(cause));
    }

    @Override
    @GuardedBy("lock")
    public void bytesRead(int processedBytes) {
      processedWindow -= processedBytes;
      if (processedWindow <= initialWindowSize * Utils.DEFAULT_WINDOW_UPDATE_RATIO) {
        int delta = initialWindowSize - processedWindow;
        window += delta;
        processedWindow += delta;
        frameWriter.windowUpdate(streamId, delta);
        frameWriter.flush();
      }
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
    @Override
    public void inboundDataReceived(okio.Buffer frame, int windowConsumed, boolean endOfStream) {
      synchronized (lock) {
        PerfMark.event("OkHttpServerTransport$FrameHandler.data", tag);
        if (endOfStream) {
          this.receivedEndOfStream = true;
        }
        window -= windowConsumed;
        super.inboundDataReceived(new OkHttpReadableBuffer(frame), endOfStream);
      }
    }

    /** Must be called with holding the transport lock. */
    @Override
    public void inboundRstReceived(Status status) {
      PerfMark.event("OkHttpServerTransport$FrameHandler.rstStream", tag);
      transportReportStatus(status);
    }

    /** Must be called with holding the transport lock. */
    @Override
    public boolean hasReceivedEndOfStream() {
      synchronized (lock) {
        return receivedEndOfStream;
      }
    }

    /** Must be called with holding the transport lock. */
    @Override
    public int inboundWindowAvailable() {
      synchronized (lock) {
        return window;
      }
    }

    @GuardedBy("lock")
    private void sendBuffer(Buffer buffer, boolean flush) {
      if (cancelSent) {
        return;
      }
      // If buffer > frameWriter.maxDataLength() the flow-controller will ensure that it is
      // properly chunked.
      outboundFlow.data(false, outboundFlowState, buffer, flush);
    }

    @GuardedBy("lock")
    private void sendHeaders(List<Header> responseHeaders) {
      frameWriter.synReply(false, streamId, responseHeaders);
      frameWriter.flush();
    }

    @GuardedBy("lock")
    private void sendTrailers(List<Header> responseTrailers) {
      outboundFlow.notifyWhenNoPendingData(
          outboundFlowState, () -> sendTrailersAfterFlowControlled(responseTrailers));
    }

    private void sendTrailersAfterFlowControlled(List<Header> responseTrailers) {
      synchronized (lock) {
        frameWriter.synReply(true, streamId, responseTrailers);
        if (!receivedEndOfStream) {
          frameWriter.rstStream(streamId, ErrorCode.NO_ERROR);
        }
        transport.streamClosed(streamId, /*flush=*/ true);
        complete();
      }
    }

    @GuardedBy("lock")
    private void cancel(ErrorCode http2Error, Status reason) {
      if (cancelSent) {
        return;
      }
      cancelSent = true;
      frameWriter.rstStream(streamId, http2Error);
      transportReportStatus(reason);
      transport.streamClosed(streamId, /*flush=*/ true);
    }

    @Override
    public OutboundFlowController.StreamState getOutboundFlowState() {
      return outboundFlowState;
    }
  }
}
