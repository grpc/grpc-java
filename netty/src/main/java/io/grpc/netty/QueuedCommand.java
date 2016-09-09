/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import com.google.common.base.Objects;

import io.grpc.Status;
import io.grpc.internal.AbstractStream2;
import io.grpc.internal.ClientTransport.PingCallback;
import io.grpc.internal.Stream;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.Recycler;

import java.util.concurrent.Executor;

/**
 * Simple wrapper type around a command and its optional completion listener.
 */
interface QueuedCommand {

  /**
   * Returns the promise beeing notified of the success/failure of the write.
   */
  ChannelPromise promise();

  /**
   * Sets the promise.
   */
  void promise(ChannelPromise promise);

  /**
   * Returns this command to the command pool.
   */
  void recycle();

  static final class UnionCommand implements QueuedCommand, CancelClientStreamCmd,
      CancelServerStreamCmd, CreateStreamCmd, ForcefulCloseCmd, GracefulCloseCmd,
      RequestMessagesCmd, SendPingCmd, SendResponseHeadersCmd, SendGrpcFrameCmd, NoopCmd {

    private static final Recycler<UnionCommand> recycler = new Recycler<UnionCommand>() {

      @Override
      protected UnionCommand newObject(Recycler.Handle<UnionCommand> handle) {
        return new UnionCommand(handle);
      }
    };

    private final Recycler.Handle<UnionCommand> handle;

    enum CmdType {
      CANCEL_CLIENT_STREAM,
      CANCEL_SERVER_STREAM,
      CREATE_STREAM,
      FORCEFUL_CLOSE,
      GRACEFUL_CLOSE,
      REQUEST_MESSAGES,
      SEND_PING,
      SEND_RESPONSE_HEADERS,
      SEND_GRPC_FRAME,
      NOOP,
      ;
    }

    private Object arg0;
    private Object arg1;
    private int theInt;
    private boolean theBoolean;
    private CmdType cmdType;

    private ChannelPromise promise;

    private UnionCommand(Recycler.Handle<UnionCommand> handle) {
      this.handle = handle;
    }

    @Override
    public void recycle() {
      arg0 = null;
      arg1 = null;
      cmdType = null;
      theInt = 0;
      theBoolean = false;
      promise(null);
      handle.recycle(this);
    }

    @Override
    public void promise(ChannelPromise promise) {
      this.promise = promise;
    }

    @Override
    public ChannelPromise promise() {
      return promise;
    }

    CmdType type() {
      return cmdType;
    }

    static CancelClientStreamCmd newCancelClientStreamCmd(NettyClientStream stream, Status reason) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = stream;
      cmd.arg1 = reason;
      cmd.cmdType = CmdType.CANCEL_CLIENT_STREAM;
      return cmd;
    }

    @Override
    public NettyClientStream cancelClientStreamCmdStream() {
      return (NettyClientStream) arg0;
    }

    @Override
    public Status cancelClientStreamCmdReason() {
      return (Status) arg1;
    }

    static CancelServerStreamCmd newCancelServerStreamCmd(
        NettyServerStream.TransportState transportState, Status reason) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = transportState;
      cmd.arg1 = reason;
      cmd.cmdType = CmdType.CANCEL_SERVER_STREAM;
      return cmd;
    }

    @Override
    public NettyServerStream.TransportState cancelServerStreamCmdTransportState() {
      return (NettyServerStream.TransportState) arg0;
    }

    @Override
    public Status cancelServerStreamCmdReason() {
      return (Status) arg1;
    }

    static CreateStreamCmd newCreateStreamCmd(
        Http2Headers headers, NettyClientStream clientStream) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = headers;
      cmd.arg1 = clientStream;
      cmd.cmdType = CmdType.CREATE_STREAM;
      return cmd;
    }

    @Override
    public Http2Headers createStreamCmdHeaders() {
      return (Http2Headers) arg0;
    }

    @Override
    public NettyClientStream createStreamCmdStream() {
      return (NettyClientStream) arg1;
    }

    static ForcefulCloseCmd newForcefulCloseCmd(Status status) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = status;
      cmd.cmdType = CmdType.FORCEFUL_CLOSE;
      return cmd;
    }

    @Override
    public Status forcefulCloseCmdStatus() {
      return (Status) arg0;
    }

    static GracefulCloseCmd newGracefulCloseCmd(Status status) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = status;
      cmd.cmdType = CmdType.GRACEFUL_CLOSE;
      return cmd;
    }

    @Override
    public Status gracefulCloseCmdStatus() {
      return (Status) arg0;
    }

    static RequestMessagesCmd newRequestMessagesCmd(Stream stream, int numMessages) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = stream;
      cmd.theInt = numMessages;
      cmd.cmdType = CmdType.REQUEST_MESSAGES;
      return cmd;
    }

    static RequestMessagesCmd newRequestMessagesCmd(
        AbstractStream2.TransportState state, int numMessages) {
      UnionCommand cmd = recycler.get();
      cmd.arg1 = state;
      cmd.theInt = numMessages;
      cmd.cmdType = CmdType.REQUEST_MESSAGES;
      return cmd;
    }

    @Override
    public void requestMessagesCmdRequestMessages() {
      if (arg0 != null) {
        ((Stream) arg0).request(theInt);
      } else {
        ((AbstractStream2.TransportState) arg1).requestMessagesFromDeframer(theInt);
      }
    }

    static SendPingCmd newSendPingCmd(PingCallback callback, Executor executor) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = callback;
      cmd.arg1 = executor;
      cmd.cmdType = CmdType.SEND_PING;
      return cmd;
    }

    @Override
    public PingCallback sendPingCmdCallback() {
      return (PingCallback) arg0;
    }

    @Override
    public Executor sendPingCmdExecutor() {
      return (Executor) arg1;
    }

    static SendResponseHeadersCmd newSendResponseHeadersCmd(
        StreamIdHolder stream, Http2Headers headers, boolean endOfStream) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = stream;
      cmd.arg1 = headers;
      cmd.theBoolean = endOfStream;
      cmd.cmdType = CmdType.SEND_RESPONSE_HEADERS;
      return cmd;
    }

    @Override
    public StreamIdHolder sendResponseHeadersCmdStream() {
      return (StreamIdHolder) arg0;
    }

    @Override
    public Http2Headers sendResponseHeadersCmdHeaders() {
      return (Http2Headers) arg1;
    }

    @Override
    public boolean sendResponseHeadersCmdEndOfStream() {
      return theBoolean;
    }

    static SendGrpcFrameCmd newSendGrpcFrameCmd(
        StreamIdHolder stream, ByteBuf content, boolean endStream) {
      UnionCommand cmd = recycler.get();
      cmd.arg0 = stream;
      cmd.arg1 = content;
      cmd.theBoolean = endStream;
      cmd.cmdType = CmdType.SEND_GRPC_FRAME;
      return cmd;
    }

    @Override
    public boolean sendGrpcFrameCmdEndStream() {
      return theBoolean;
    }

    @Override
    public int sendGrpcFrameCmdStreamId() {
      return ((StreamIdHolder) arg0).id();
    }

    @Override
    public ByteBuf sendGrpcFrameCmdContent() {
      return (ByteBuf) arg1;
    }

    static NoopCmd newNoopCmd() {
      UnionCommand cmd = recycler.get();
      cmd.cmdType = CmdType.NOOP;
      return cmd;
    }

    // For testing, not intended for regular use.
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof UnionCommand)) {
        return false;
      }
      UnionCommand that = (UnionCommand) other;
      return Objects.equal(this.arg0, that.arg0)
          && Objects.equal(this.arg1, that.arg1)
          && this.theBoolean == that.theBoolean
          && this.theInt == that.theInt
          && Objects.equal(this.cmdType, that.cmdType);
    }

    // For testing, not intended for regular use.
    @Override
    public int hashCode() {
      return cmdType.hashCode();
    }

    @Override
    public String toString() {
      return cmdType != null ? cmdType.name() : "null";
    }
  }

  interface CancelClientStreamCmd extends QueuedCommand {
    NettyClientStream cancelClientStreamCmdStream();

    Status cancelClientStreamCmdReason();
  }

  interface CancelServerStreamCmd extends QueuedCommand {
    NettyServerStream.TransportState cancelServerStreamCmdTransportState();

    Status cancelServerStreamCmdReason();
  }

  interface CreateStreamCmd extends QueuedCommand {
    Http2Headers createStreamCmdHeaders();

    NettyClientStream createStreamCmdStream();
  }

  interface ForcefulCloseCmd extends QueuedCommand {
    Status forcefulCloseCmdStatus();
  }

  interface GracefulCloseCmd extends QueuedCommand {
    Status gracefulCloseCmdStatus();
  }

  interface RequestMessagesCmd extends QueuedCommand {
    void requestMessagesCmdRequestMessages();
  }

  interface SendPingCmd extends QueuedCommand {
    PingCallback sendPingCmdCallback();

    Executor sendPingCmdExecutor();
  }

  interface SendResponseHeadersCmd extends QueuedCommand {
    StreamIdHolder sendResponseHeadersCmdStream();

    Http2Headers sendResponseHeadersCmdHeaders();

    boolean sendResponseHeadersCmdEndOfStream();
  }

  interface SendGrpcFrameCmd extends QueuedCommand {
    boolean sendGrpcFrameCmdEndStream();

    ByteBuf sendGrpcFrameCmdContent();

    int sendGrpcFrameCmdStreamId();
  }

  interface NoopCmd extends QueuedCommand {
  }
}
