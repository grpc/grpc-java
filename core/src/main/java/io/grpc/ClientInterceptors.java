/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Utility methods for working with {@link ClientInterceptor}s.
 */
public class ClientInterceptors {

  // Prevent instantiation
  private ClientInterceptors() {}

  /**
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel.
   *
   * @param channel the underlying channel to intercept.
   * @param interceptors array of interceptors to bind to {@code channel}.
   * @return a new channel instance with the interceptors applied.
   */
  public static Channel intercept(Channel channel, ClientInterceptor... interceptors) {
    return intercept(channel, Arrays.asList(interceptors));
  }

  /**
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel.
   *
   * @param channel the underlying channel to intercept.
   * @param interceptors a list of interceptors to bind to {@code channel}.
   * @return a new channel instance with the interceptors applied.
   */
  public static Channel intercept(Channel channel, List<ClientInterceptor> interceptors) {
    Preconditions.checkNotNull(channel);
    if (interceptors.isEmpty()) {
      return channel;
    }
    return new InterceptorChannel(channel, interceptors);
  }

  private static class InterceptorChannel implements Channel {
    private final Channel channel;
    private final Iterable<ClientInterceptor> interceptors;

    private InterceptorChannel(Channel channel, List<ClientInterceptor> interceptors) {
      this.channel = channel;
      this.interceptors = ImmutableList.copyOf(interceptors);
    }

    @Override
    public <ReqT, RespT> Call<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method) {
      return new ProcessInterceptorChannel(channel, interceptors).newCall(method);
    }
  }

  private static class ProcessInterceptorChannel implements Channel {
    private final Channel channel;
    private Iterator<ClientInterceptor> interceptors;

    private ProcessInterceptorChannel(Channel channel, Iterable<ClientInterceptor> interceptors) {
      this.channel = channel;
      this.interceptors = interceptors.iterator();
    }

    @Override
    public <ReqT, RespT> Call<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method) {
      if (interceptors != null && interceptors.hasNext()) {
        return interceptors.next().interceptCall(method, this);
      } else {
        Preconditions.checkState(interceptors != null,
            "The channel has already been called. "
            + "Some interceptor must have called on \"next\" twice.");
        interceptors = null;
        return channel.newCall(method);
      }
    }
  }

  /**
   * A {@link Call} which forwards all of it's methods to another {@link Call}.
   */
  public abstract static class ForwardingCall<ReqT, RespT> extends Call<ReqT, RespT> {

    /**
     * Returns the delegated {@code Call}
     */
    protected abstract Call<ReqT, RespT> delegate();

    @Override
    public void start(Listener<RespT> responseListener, Metadata.Headers headers) {
      this.delegate().start(responseListener, headers);
    }

    @Override
    public void request(int numMessages) {
      this.delegate().request(numMessages);
    }

    @Override
    public void cancel() {
      this.delegate().cancel();
    }

    @Override
    public void halfClose() {
      this.delegate().halfClose();
    }

    @Override
    public void sendPayload(ReqT payload) {
      this.delegate().sendPayload(payload);
    }
  }

  private static final Call<Object, Object> NOOP_CALL = new Call<Object, Object>() {
    @Override
    public void start(Listener<Object> responseListener, Metadata.Headers headers) { }

    @Override
    public void request(int numMessages) { }

    @Override
    public void cancel() { }

    @Override
    public void halfClose() { }

    @Override
    public void sendPayload(Object payload) { }
  };

  /**
   * Returns a {@code Call} that does absolutely nothing to be used by {@link ForwardingCall} that
   * has failed to start.
   *
   * <p>If a {@code ForwardingCall} fails in {@code start()} and won't start the real delegate
   * {@code Call}, the caller may still try to use the {@code ForwardingCall} and may get {@code
   * IllegalStateException}, before it's notified of the error through the listener.  {@link
   * ForwardingCall#delegate()} should return {@code noopCall()} in this case to prevent the {@code
   * IllegalStateException}.
   */
  @SuppressWarnings("unchecked")
  public static <ReqT, RespT> Call<ReqT, RespT> noopCall() {
    return (Call<ReqT, RespT>) NOOP_CALL;
  }

  /**
   * A {@link Call.Listener} which forwards all of its methods to another
   * {@link Call.Listener}.
   */
  public abstract static class ForwardingListener<T> extends Call.Listener<T> {

    /**
     * Returns the delegated {@code Call.Listener}
     */
    protected abstract Call.Listener<T> delegate();

    @Override
    public void onHeaders(Metadata.Headers headers) {
      delegate().onHeaders(headers);
    }

    @Override
    public void onPayload(T payload) {
      delegate().onPayload(payload);
    }

    @Override
    public void onClose(Status status, Metadata.Trailers trailers) {
      delegate().onClose(status, trailers);
    }

    @Override
    public void onReady(int numMessages) {
      delegate().onReady(numMessages);
    }
  }
}
