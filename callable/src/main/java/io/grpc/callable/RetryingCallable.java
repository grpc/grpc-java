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

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

/**
 * Helper type for the implementation of {@link Callable} methods. Please see there first for the
 * specification of what this is doing. This class is concerned with the how.
 *
 * <p>Implementation of the retrying callable.
 */
class RetryingCallable<RequestT, ResponseT> extends Callable<RequestT, ResponseT> {

  // TODO(wgg): make the parameters below configurable. They are currently taken from
  // http://en.wikipedia.org/wiki/Exponential_backoff.

  private static final int SLOT_TIME_MILLIS = 2;
  private static final int TRUNCATE_AFTER = 10;
  private static final int MAX_ATTEMPTS = 16;
  private static final Random randomGenerator = new Random(0);

  private final Callable<RequestT, ResponseT> callable;

  RetryingCallable(Callable<RequestT, ResponseT> callable) {
    this.callable = callable;
  }

  @Override public String toString() {
    return String.format("retrying(%s)", callable.toString());
  }

  @Override
  @Nullable
  public CallableDescriptor<RequestT, ResponseT> getDescriptor() {
    return callable.getDescriptor();
  }

  @Override
  @Nullable
  public Channel getBoundChannel() {
    return callable.getBoundChannel();
  }

  @Override public ClientCall<RequestT, ResponseT> newCall(Channel channel) {
    return new RetryingCall(channel);
  }

  private static boolean canRetry(Status status) {
    return status.getCode() == Status.Code.UNAVAILABLE;
  }

  /**
   * Class implementing the call for retry.
   *
   * <p>This remembers all actions from the caller in order to replay the call if needed. No output
   * will be produced until the call has successfully ended. Thus this call requires full buffering
   * of inputs and outputs,
   */
  private class RetryingCall extends CompoundClientCall<RequestT, ResponseT, ResponseT> {

    private final Channel channel;
    private ClientCall.Listener<ResponseT> listener;
    private int requested;
    private Metadata requestHeaders;
    private final List<RequestT> requestPayloads = Lists.newArrayList();
    private final List<ResponseT> responsePayloads = Lists.newArrayList();
    private Metadata responseHeaders;
    private int attempt;
    private ClientCall<RequestT, ResponseT> currentCall;

    private RetryingCall(Channel channel) {
      this.channel = channel;
    }

    @Override
    public void start(ClientCall.Listener<ResponseT> listener, Metadata headers) {
      this.listener = listener;
      requestHeaders = headers;
      currentCall = callable.newCall(channel);
      currentCall.start(listener(), headers);
    }

    @Override
    public void request(int numMessages) {
      requested += numMessages;
      currentCall.request(numMessages);
    }

    @Override
    public void cancel() {
      currentCall.cancel();
    }

    @Override
    public void halfClose() {
      currentCall.halfClose();
    }

    @Override
    public void sendMessage(RequestT message) {
      requestPayloads.add(message);
      currentCall.sendMessage(message);
    }

    @Override
    public void onHeaders(Metadata headers) {
      responseHeaders = headers;
    }

    @Override
    void onMessage(ResponseT message) {
      responsePayloads.add(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk() || !canRetry(status) || attempt >= MAX_ATTEMPTS) {
        // Call succeeded or failed non-transiently or failed too often; feed underlying listener
        // with the result.
        if (status.isOk()) {
          if (responseHeaders != null) {
            listener.onHeaders(responseHeaders);
          }
          for (ResponseT response : responsePayloads) {
            listener.onMessage(response);
          }
        }
        listener.onClose(status, trailers);
        return;
      }

      // Sleep using duration calculated by exponential backoff algorithm.
      attempt++;
      int slots = 1 << (attempt - 1 > TRUNCATE_AFTER ? TRUNCATE_AFTER : attempt - 1);
      int slot = randomGenerator.nextInt(slots);
      if (slot > 0) {
        try {
          Thread.sleep(SLOT_TIME_MILLIS * slot);
        } catch (InterruptedException e) {
          throw Throwables.propagate(e);
        }
      }

      // Start call again.
      responseHeaders = null;
      responsePayloads.clear();
      currentCall = callable.newCall(channel);
      currentCall.start(listener(), requestHeaders);
      currentCall.request(requested);
      for (RequestT request : requestPayloads) {
        currentCall.sendMessage(request);
      }
      currentCall.halfClose();
    }
  }
}
