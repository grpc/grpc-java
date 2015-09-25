/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.callable;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;

import javax.annotation.Nullable;

/**
 * Helper type for the implementation of {@link Callable} methods. Please see there first for the
 * specification of what this is doing. This class is concerned with the how.
 *
 * <p>Implements the followedBy callable, which executes two callables in parallel, piping output
 * from the first to the second.
 */
class FollowedByCallable<RequestT, InterT, ResponseT> extends Callable<RequestT, ResponseT> {

  private final Callable<RequestT, InterT> first;
  private final Callable<InterT, ResponseT> second;

  FollowedByCallable(Callable<RequestT, InterT> first, Callable<InterT, ResponseT> second) {
    this.first = first;
    this.second = second;
  }

  @Override
  public String toString() {
    return String.format("followedBy(%s, %s)", first, second);
  }

  @Override
  @Nullable
  public Channel getBoundChannel() {
    // Inherit a bound channel from operands.
    Channel channel = first.getBoundChannel();
    if (channel != null) {
      return channel;
    }
    return second.getBoundChannel();
  }

  @Override
  public ClientCall<RequestT, ResponseT> newCall(Channel channel) {
    return new FollowedByCall(channel);
  }

  /**
   * Both calls are started in parallel, and the output from the first is immediately piped as input
   * into the second. As we don't know the required input for the second call, we request all output
   * from the first as soon as some output for the composite is requested.
   */
  private class FollowedByCall extends CompoundClientCall<RequestT, ResponseT, InterT> {

    private Channel channel;
    private ClientCall<RequestT, InterT> firstCall;
    private ClientCall<InterT, ResponseT> secondCall;
    private ClientCall.Listener<ResponseT> listener;

    private FollowedByCall(Channel channel) {
      this.channel = channel;
    }

    @Override
    public void start(ClientCall.Listener<ResponseT> listener, Metadata headers) {
      this.listener = listener;
      this.firstCall = first.newCall(channel);
      this.secondCall = second.newCall(channel);

      // This instance's listener will receive output from the first call.
      this.firstCall.start(this.listener(), headers);

      // The ForwardingCallable listener will receive output from the second call.
      this.secondCall.start(listener, headers);
    }

    @Override
    public void request(int numMessages) {
      // We don't know how much inputs the second call needs, so we request all what is available.
      firstCall.request(Integer.MAX_VALUE);
      secondCall.request(numMessages);
    }

    @Override
    public void cancel() {
      firstCall.cancel();
      secondCall.cancel();
    }

    @Override
    public void sendMessage(RequestT message) {
      firstCall.sendMessage(message);
    }

    @Override
    public void halfClose() {
      firstCall.halfClose();
    }

    @Override
    public void onMessage(InterT message) {
      secondCall.sendMessage(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk()) {
        secondCall.halfClose();
        return;
      }
      secondCall.cancel();
      listener.onClose(status, trailers);
    }
  }
}
