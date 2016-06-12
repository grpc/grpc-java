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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import io.grpc.internal.IdempotentRetryPolicy;
import io.grpc.internal.RetryPolicy;
import io.grpc.internal.SharedResourceHolder;

/**
 * Utility methods for working with {@link ClientInterceptor}s.
 */
public class ClientInterceptors {

  // Prevent instantiation
  private ClientInterceptors() {}

  /**
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel. The first interceptor will have its {@link ClientInterceptor#interceptCall}
   * called first.
   *
   * @param channel the underlying channel to intercept.
   * @param interceptors array of interceptors to bind to {@code channel}.
   * @return a new channel instance with the interceptors applied.
   */
  public static Channel interceptForward(Channel channel, ClientInterceptor... interceptors) {
    return interceptForward(channel, Arrays.asList(interceptors));
  }

  /**
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel. The first interceptor will have its {@link ClientInterceptor#interceptCall}
   * called first.
   *
   * @param channel the underlying channel to intercept.
   * @param interceptors a list of interceptors to bind to {@code channel}.
   * @return a new channel instance with the interceptors applied.
   */
  public static Channel interceptForward(Channel channel,
                                         List<? extends ClientInterceptor> interceptors) {
    List<? extends ClientInterceptor> copy = new ArrayList<ClientInterceptor>(interceptors);
    Collections.reverse(copy);
    return intercept(channel, copy);
  }

  /**
   * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
   * given channel. The last interceptor will have its {@link ClientInterceptor#interceptCall}
   * called first.
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
   * given channel. The last interceptor will have its {@link ClientInterceptor#interceptCall}
   * called first.
   *
   * @param channel the underlying channel to intercept.
   * @param interceptors a list of interceptors to bind to {@code channel}.
   * @return a new channel instance with the interceptors applied.
   */
  public static Channel intercept(Channel channel, List<? extends ClientInterceptor> interceptors) {
    Preconditions.checkNotNull(channel);
    for (ClientInterceptor interceptor : interceptors) {
      channel = new InterceptorChannel(channel, interceptor);
    }
    return channel;
  }

  private static class InterceptorChannel extends Channel {
    private final Channel channel;
    private final ClientInterceptor interceptor;

    private InterceptorChannel(Channel channel, ClientInterceptor interceptor) {
      this.channel = channel;
      this.interceptor = Preconditions.checkNotNull(interceptor, "interceptor");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      return interceptor.interceptCall(method, callOptions, channel);
    }

    @Override
    public String authority() {
      return channel.authority();
    }
  }

  private static final ClientCall<Object, Object> NOOP_CALL = new ClientCall<Object, Object>() {
    @Override
    public void start(Listener<Object> responseListener, Metadata headers) {}

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(String message, Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(Object message) {}

    /**
     * Always returns {@code false}, since this is only used when the startup of the {@link
     * ClientCall} fails (i.e. the {@link ClientCall} is closed).
     */
    @Override
    public boolean isReady() {
      return false;
    }
  };

  /**
   * A {@link io.grpc.ForwardingClientCall} that delivers exceptions from its start logic to the
   * call listener.
   *
   * <p>{@link ClientCall#start(ClientCall.Listener, Metadata)} should not throw any
   * exception other than those caused by misuse, e.g., {@link IllegalStateException}.  {@code
   * CheckedForwardingClientCall} provides {@code checkedStart()} in which throwing exceptions is
   * allowed.
   */
  public abstract static class CheckedForwardingClientCall<ReqT, RespT>
      extends io.grpc.ForwardingClientCall<ReqT, RespT> {

    private ClientCall<ReqT, RespT> delegate;

    /**
     * Subclasses implement the start logic here that would normally belong to {@code start()}.
     *
     * <p>Implementation should call {@code this.delegate().start()} in the normal path. Exceptions
     * may safely be thrown prior to calling {@code this.delegate().start()}. Such exceptions will
     * be handled by {@code CheckedForwardingClientCall} and be delivered to {@code
     * responseListener}.  Exceptions <em>must not</em> be thrown after calling {@code
     * this.delegate().start()}, as this can result in {@link ClientCall.Listener#onClose} being
     * called multiple times.
     */
    protected abstract void checkedStart(Listener<RespT> responseListener, Metadata headers)
        throws Exception;

    protected CheckedForwardingClientCall(ClientCall<ReqT, RespT> delegate) {
      this.delegate = delegate;
    }

    @Override
    protected final ClientCall<ReqT, RespT> delegate() {
      return delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final void start(Listener<RespT> responseListener, Metadata headers) {
      try {
        checkedStart(responseListener, headers);
      } catch (Exception e) {
        // Because start() doesn't throw, the caller may still try to call other methods on this
        // call object. Passing these invocations to the original delegate will cause
        // IllegalStateException because delegate().start() was not called. We switch the delegate
        // to a NO-OP one to prevent the IllegalStateException. The user will finally get notified
        // about the error through the listener.
        delegate = (ClientCall<ReqT, RespT>) NOOP_CALL;
        responseListener.onClose(Status.fromThrowable(e), new Metadata());
      }
    }
  }

  public static class RetryingInterceptor implements ClientInterceptor {
    // TODO(lukaszx0) have default logger for this one to give more visibility into when/what gets
    // retried?
    // private final Logger logger = Logger.getLogger(RetryingInterceptor.class.getName());
    private final RetryPolicy.Provider retryPolicyProvider;

    public RetryingInterceptor(RetryPolicy.Provider retryPolicyProvider) {
      this.retryPolicyProvider = retryPolicyProvider;
    }

    public RetryingInterceptor() {
      this.retryPolicyProvider = new IdempotentRetryPolicy.Provider();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

      // Only UNARY calls can be retried, streaming call errors should be handled by user.
      if (method.getType() != MethodDescriptor.MethodType.UNARY) {
        return next.newCall(method, callOptions);
      }

      return new RetryingCall<ReqT, RespT>(retryPolicyProvider, method, callOptions, next, Context.current());
    }

    private class RetryingCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
      private final RetryPolicy retryPolicy;
      private final MethodDescriptor<ReqT, RespT> method;
      private final CallOptions callOptions;
      private final Channel channel;
      private final Context context;
      private final ScheduledExecutorService scheduledExecutor;
      private Listener<RespT> responseListener;
      private Metadata requestHeaders;
      private ReqT requestMessage;
      private boolean compressionEnabled;
      private final Queue<AttemptListener> attemptListeners =
              new ConcurrentLinkedQueue<AttemptListener>();
      private volatile AttemptListener latestResponse;
      private volatile ScheduledFuture<?> retryTask;

      RetryingCall(RetryPolicy.Provider retryPolicyProvider, MethodDescriptor<ReqT, RespT> method,
                   CallOptions callOptions, Channel channel, Context context) {
        this.retryPolicy = retryPolicyProvider.get();
        this.method = method;
        this.callOptions = callOptions;
        this.channel = channel;
        this.context = context;

        this.scheduledExecutor = SharedResourceHolder.get(TIMER_SERVICE);
      }

      @Override
      public void start(Listener<RespT> listener, Metadata headers) {
        checkState(attemptListeners.isEmpty());
        checkState(responseListener == null);
        checkState(requestHeaders == null);
        responseListener = listener;
        requestHeaders = headers;
        ClientCall<ReqT, RespT> firstCall = channel.newCall(method, callOptions);
        AttemptListener attemptListener = new AttemptListener(firstCall);
        attemptListeners.add(attemptListener);
        firstCall.start(attemptListener, headers);
      }

      @Override
      public void request(int numMessages) {
        lastCall().request(numMessages);
      }

      @Override
      public void cancel() {
        for (AttemptListener attempt : attemptListeners) {
          attempt.call.cancel();
        }
        if (retryTask != null) {
          retryTask.cancel(true);
        }
      }

      @Override
      public void halfClose() {
        lastCall().halfClose();
      }

      @Override
      public void sendMessage(ReqT message) {
        checkState(requestMessage == null);
        requestMessage = message;
        lastCall().sendMessage(message);
      }

      @Override
      public boolean isReady() {
        return lastCall().isReady();
      }

      @Override
      public void setMessageCompression(boolean enabled) {
        compressionEnabled = enabled;
        lastCall().setMessageCompression(enabled);
      }

      private void maybeRetry(AttemptListener attempt) {
        Status status = attempt.responseStatus;
        if (status.isOk() || !retryPolicy.isRetryable(status, method, callOptions)) {
          useResponse(attempt);
          return;
        }

        long nextBackoffMillis = retryPolicy.getNextBackoffMillis();
        long nextBackoffNano = TimeUnit.MILLISECONDS.toNanos(nextBackoffMillis);
        long deadlineNanoTime =  firstNonNull(callOptions.getDeadlineNanoTime(), -1).longValue();

        Status.Code code = status.getCode();
        if (code == Status.Code.CANCELLED || code == Status.Code.DEADLINE_EXCEEDED ||
            (deadlineNanoTime > -1 && deadlineNanoTime < nextBackoffNano)) {
          AttemptListener latest = latestResponse;
          if (latest != null) {
            useResponse(latest);
          } else {
            useResponse(attempt);
          }
          return;
        }
        latestResponse = attempt;
        retryTask = scheduledExecutor.schedule(context.wrap(new Runnable() {
          @Override
          public void run() {
            ClientCall<ReqT, RespT> nextCall = channel.newCall(method, callOptions);
            AttemptListener nextAttemptListener = new AttemptListener(nextCall);
            attemptListeners.add(nextAttemptListener);
            nextCall.start(nextAttemptListener, requestHeaders);
            nextCall.setMessageCompression(compressionEnabled);
            nextCall.sendMessage(requestMessage);
            // TODO(lukaszx0): In other places, we're preemptively fetching two requests for
            // unary calls should we do the same here?
            nextCall.request(1);
            nextCall.halfClose();
          }
        }), nextBackoffMillis, TimeUnit.MILLISECONDS);
      }

      private void useResponse(AttemptListener attempt) {
        responseListener.onHeaders(attempt.responseHeaders);
        if (attempt.responseMessage != null) {
          responseListener.onMessage(attempt.responseMessage);
        }
        responseListener.onClose(attempt.responseStatus, attempt.responseTrailers);
      }

      private ClientCall<ReqT, RespT> lastCall() {
        checkState(!attemptListeners.isEmpty());
        return attemptListeners.peek().call;
      }

      private class AttemptListener extends ClientCall.Listener<RespT> {
        final ClientCall<ReqT, RespT> call;
        Metadata responseHeaders;
        RespT responseMessage;
        Status responseStatus;
        Metadata responseTrailers;

        AttemptListener(ClientCall<ReqT, RespT> call) {
          this.call = call;
        }

        @Override
        public void onHeaders(Metadata headers) {
          responseHeaders = headers;
        }

        @Override
        public void onMessage(RespT message) {
          responseMessage = message;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
          responseStatus = status;
          responseTrailers = trailers;
          maybeRetry(this);
        }

        @Override
        public void onReady() {
          // Pass-through to original listener.
          // TODO(lukaszx0): Maybe only on first attempt?
          responseListener.onReady();
        }
      }
    }
  }
}
