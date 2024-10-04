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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.Tag;
import io.perfmark.TaskCloseable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server stream for a Netty HTTP2 transport. Must only be called from the sending application
 * thread.
 */
class NettyServerStream extends AbstractServerStream {
  private static final Logger log = Logger.getLogger(NettyServerStream.class.getName());

  private final Sink sink = new Sink();
  private final TransportState state;
  private final WriteQueue writeQueue;
  private final Attributes attributes;
  private final String authority;
  private final int streamId;

  public NettyServerStream(
      Channel channel,
      TransportState state,
      Attributes transportAttrs,
      String authority,
      StatsTraceContext statsTraceCtx) {
    super(new NettyWritableBufferAllocator(channel.alloc()), statsTraceCtx);
    this.state = checkNotNull(state, "transportState");
    this.writeQueue = state.handler.getWriteQueue();
    this.attributes = checkNotNull(transportAttrs);
    this.authority = authority;
    // Read the id early to avoid reading transportState later.
    this.streamId = transportState().id();
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
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public String getAuthority() {
    return authority;
  }

  private class Sink implements AbstractServerStream.Sink {
    @Override
    public void writeHeaders(Metadata headers, boolean flush) {
      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerStream$Sink.writeHeaders")) {
        Http2Headers http2headers = Utils.convertServerHeaders(headers);
        SendResponseHeadersCommand headersCommand =
            SendResponseHeadersCommand.createHeaders(transportState(), http2headers);
        writeQueue.enqueue(headersCommand, flush)
            .addListener((ChannelFutureListener) transportState()::handleWriteFutureFailures);
      }
    }

    @Override
    public void writeFrame(WritableBuffer frame, boolean flush, final int numMessages) {
      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerStream$Sink.writeFrame")) {
        Preconditions.checkArgument(numMessages >= 0);
        ByteBuf bytebuf = ((NettyWritableBuffer) frame).bytebuf().touch();
        final int numBytes = bytebuf.readableBytes();
        // Add the bytes to outbound flow control.
        onSendingBytes(numBytes);
        ChannelFutureListener failureListener =
            future -> transportState().onWriteFrameData(future, numMessages, numBytes);
        writeQueue.enqueue(new SendGrpcFrameCommand(transportState(), bytebuf, false), flush)
            .addListener(failureListener);
      }
    }

    @Override
    public void writeTrailers(Metadata trailers, boolean headersSent, Status status) {
      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerStream$Sink.writeTrailers")) {
        Http2Headers http2Trailers = Utils.convertTrailers(trailers, headersSent);
        SendResponseHeadersCommand trailersCommand =
            SendResponseHeadersCommand.createTrailers(transportState(), http2Trailers, status);
        writeQueue.enqueue(trailersCommand, true)
            .addListener((ChannelFutureListener) transportState()::handleWriteFutureFailures);
      }
    }

    @Override
    public void cancel(Status status) {
      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerStream$Sink.cancel")) {
        writeQueue.enqueue(CancelServerStreamCommand.withReset(transportState(), status), true);
      }
    }
  }

  /** This should only be called from the transport thread. */
  public static class TransportState extends AbstractServerStream.TransportState
      implements StreamIdHolder {
    private final Http2Stream http2Stream;
    private final NettyServerHandler handler;
    private final EventLoop eventLoop;
    private final Tag tag;

    public TransportState(
        NettyServerHandler handler,
        EventLoop eventLoop,
        Http2Stream http2Stream,
        int maxMessageSize,
        StatsTraceContext statsTraceCtx,
        TransportTracer transportTracer,
        String methodName) {
      super(maxMessageSize, statsTraceCtx, transportTracer);
      this.http2Stream = checkNotNull(http2Stream, "http2Stream");
      this.handler = checkNotNull(handler, "handler");
      this.eventLoop = eventLoop;
      this.tag = PerfMark.createTag(methodName, http2Stream.id());
    }

    @Override
    public void runOnTransportThread(final Runnable r) {
      if (eventLoop.inEventLoop()) {
        r.run();
      } else {
        final Link link = PerfMark.linkOut();
        eventLoop.execute(new Runnable() {
          @Override
          public void run() {
            try (TaskCloseable ignore =
                     PerfMark.traceTask("NettyServerStream$TransportState.runOnTransportThread")) {
              PerfMark.attachTag(tag);
              PerfMark.linkIn(link);
              r.run();
            }
          }
        });
      }
    }

    @Override
    public void bytesRead(int processedBytes) {
      handler.returnProcessedBytes(http2Stream, processedBytes);
      handler.getWriteQueue().scheduleFlush();
    }

    @Override
    public void deframeFailed(Throwable cause) {
      log.log(Level.WARNING, "Exception processing message", cause);
      Status status = Status.fromThrowable(cause);
      transportReportStatus(status);
      handler.getWriteQueue().enqueue(CancelServerStreamCommand.withReason(this, status), true);
    }

    private void onWriteFrameData(ChannelFuture future, int numMessages, int numBytes) {
      // Remove the bytes from outbound flow control, optionally notifying
      // the client that they can send more bytes.
      if (future.isSuccess()) {
        onSentBytes(numBytes);
        getTransportTracer().reportMessageSent(numMessages);
      } else {
        handleWriteFutureFailures(future);
      }
    }

    private void handleWriteFutureFailures(ChannelFuture future) {
      // isStreamDeallocated() check protects from spamming stream resets by scheduling multiple
      // CancelServerStreamCommand commands.
      if (future.isSuccess() || isStreamDeallocated()) {
        return;
      }

      // Future failed, fail RPC.
      // Normally we don't need to do anything on frame write failures because the cause of
      // the failed future would be an IO error that closed the stream.
      // However, we still need handle any unexpected failures raised in Netty.
      http2ProcessingFailed(Utils.statusFromThrowable(future.cause()));
    }

    /**
     * Called to process a failure in HTTP/2 processing.
     */
    protected void http2ProcessingFailed(Status status) {
      transportReportStatus(status);
      handler.getWriteQueue().enqueue(CancelServerStreamCommand.withReset(this, status), true);
    }

    void inboundDataReceived(ByteBuf frame, boolean endOfStream) {
      super.inboundDataReceived(new NettyReadableBuffer(frame.retain()), endOfStream);
    }

    @Override
    public int id() {
      return http2Stream.id();
    }

    @Override
    public Tag tag() {
      return tag;
    }
  }

  @Override
  public int streamId() {
    return streamId;
  }
}
