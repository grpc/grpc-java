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

import com.google.common.base.Preconditions;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Semaphore;

import javax.annotation.Nullable;

/**
 * Helper type for the implementation of {@link Callable} methods. Please see there first for the
 * specification of what this is doing. This class is concerned with the how.
 *
 * <p>Implementation of the pageStreaming callable.
 */
class PageStreamingCallable<RequestT, ResponseT, ResourceT> extends Callable<RequestT, ResourceT> {

  private final Callable<RequestT, ResponseT> callable;
  private final PageDescriptor<RequestT, ResponseT, ResourceT> pageDescriptor;


  PageStreamingCallable(Callable<RequestT, ResponseT> callable,
      PageDescriptor<RequestT, ResponseT, ResourceT> pageDescriptor) {
    this.callable = Preconditions.checkNotNull(callable);
    this.pageDescriptor = Preconditions.checkNotNull(pageDescriptor);
  }

  @Override public String toString() {
    return String.format("pageStreaming(%s)", callable.toString());
  }

  @Override
  @Nullable
  public Channel getBoundChannel() {
    return callable.getBoundChannel();
  }

  @Override
  public ClientCall<RequestT, ResourceT> newCall(Channel channel) {
    return new PageStreamingCall(channel);
  }

  /**
   * Class which handles both the call logic for the callable and listens to page call responses.
   *
   * <p>The implementation uses a semaphore to handle flow control, since the callable level flow
   * control via request() doesn't map 1:1 to the page call flow control. The semaphore holds at any
   * time the number of requested messages on callable level. Blocking on the semaphore happens
   * exclusively in onMessage() calls for pages. Apart of the first page call which is scheduled at
   * the time the caller half-closes, all future page calls will be triggered from onMessage() as
   * well. This avoids thread safety issues, assuming the ClientCall concurrency contract.
   */
  private class PageStreamingCall extends CompoundClientCall<RequestT, ResourceT, ResponseT> {

    private final Channel channel;
    private ClientCall.Listener<ResourceT> outerListener;
    private Metadata headers;
    private RequestT request;
    private Semaphore requestedSemaphore = new Semaphore(0);
    private ClientCall<RequestT, ResponseT> currentCall;
    private Object nextPageToken = pageDescriptor.emptyToken();
    private boolean sentClose;

    private PageStreamingCall(Channel channel) {
      this.channel = channel;
    }

    @Override
    public void start(ClientCall.Listener<ResourceT> responseListener, Metadata headers) {
      this.outerListener = responseListener;
      this.headers = headers;
      currentCall = callable.newCall(channel);
      currentCall.start(listener(), headers);
    }

    @Override
    public void request(int numMessages) {
      requestedSemaphore.release(numMessages);
    }

    @Override
    public void sendMessage(RequestT request) {
      Preconditions.checkState(this.request == null);
      this.request = request;
    }

    @Override
    public void halfClose() {
      // Trigger the call for the first page.
      requestNextPage();
    }

    @Override
    public void cancel() {
      currentCall.cancel();
      if (!sentClose) {
        outerListener.onClose(Status.CANCELLED, new Metadata());
      }
    }

    @SuppressWarnings("unchecked")
    private void requestNextPage() {
      currentCall.request(1);
      currentCall.sendMessage(pageDescriptor.injectToken(request, nextPageToken));
      currentCall.halfClose();
    }

    @Override
    public void onMessage(ResponseT response) {
      // Extract the token for the next page. If empty, there are no more pages,
      // and we set the token to null.
      Object token = pageDescriptor.extractNextToken(response);
      nextPageToken = token.equals(pageDescriptor.emptyToken()) ? null : token;

      // Deliver as much resources as have been requested. This may block via
      // our semaphore, and while we are delivering, more requests may come in.
      for (ResourceT resource : pageDescriptor.extractResources(response)) {
        try {
          requestedSemaphore.acquire();
        } catch (InterruptedException e) {
          outerListener.onClose(Status.fromThrowable(e), new Metadata());
          sentClose = true;
          currentCall.cancel();
          return;
        }
        outerListener.onMessage(resource);
      }

      // If there is a next page, create a new call and request it.
      if (nextPageToken != null) {
        currentCall = callable.newCall(channel);
        currentCall.start(listener(), headers);
        requestNextPage();
      } else {
        outerListener.onClose(Status.OK, new Metadata());
        sentClose = true;
      }
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (!status.isOk()) {
        // If there is an error, propagate it. Otherwise let onMessage determine how to continue.
        outerListener.onClose(status, trailers);
        sentClose = true;
      }
    }
  }
}

