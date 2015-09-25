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

import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;
import io.grpc.StatusException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import java.util.Iterator;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * A callable is an object which represents one or more rpc calls. Various operators on callables
 * produce new callables, representing common API programming patterns. Callables can be used to
 * directly operate against an api, or to efficiently implement wrappers for apis which add
 * additional functionality and processing.
 *
 * <p>Technically, callables are a factory for grpc {@link ClientCall} objects, and can be executed
 * by methods of the {@link ClientCalls} class. They also provide shortcuts for direct execution of
 * the callable instance.
 */
@ExperimentalApi
public abstract class Callable<RequestT, ResponseT> {

  // TODO(wrwg): Support interceptors and method option configurations.
  // TODO(wrwg): gather more feedback whether the overload with java.util.Concurrent hurts that
  //             much that we want to rename this into ClientCallable or such.

  // Subclass Contract
  // =================

  /**
   * Creates a new GRPC call from this callable. A channel may or may not be provided.
   * If a channel is not provided, the callable must be bound to a channel.
   */
  public abstract ClientCall<RequestT, ResponseT> newCall(@Nullable Channel channel);

  /**
   * Return a descriptor for this callable, or null if none available.
   */
  @Nullable public CallableDescriptor<RequestT, ResponseT> getDescriptor() {
    return null;
  }

  /**
   * Gets the channel bound to this callable, or null, if none is bound to it.
   */
  @Nullable public Channel getBoundChannel() {
    return null;
  }

  // Binding Callables
  // =================

  /**
   * Returns a callable which is bound to the given channel. Operations on the result can
   * omit the channel. If a channel is provided anyway, it overrides the bound channel.
   */
  public Callable<RequestT, ResponseT> bind(final Channel boundChannel) {
    return new Callable<RequestT, ResponseT>() {
      @Override
      public ClientCall<RequestT, ResponseT> newCall(@Nullable Channel channel) {
        if (channel == null) {
          // If the caller does not provide a channel, we use the bound one.
          channel = boundChannel;
        }
        return Callable.this.newCall(channel);
      }

      @Override
      @Nullable
      public CallableDescriptor<RequestT, ResponseT> getDescriptor() {
        return Callable.this.getDescriptor();
      }

      @Override
      @Nullable
      public Channel getBoundChannel() {
        return boundChannel;
      }
    };
  }

  // Running Callables
  // =================

  /**
   * Convenience method to run a unary callable synchronously. If no channel is provided,
   * the callable must be bound to one.
   */
  public ResponseT call(@Nullable Channel channel, RequestT request) {
    return ClientCalls.blockingUnaryCall(newCall(channel), request);
  }

  /**
   * Convenience method to run a unary callable synchronously, without channel. Requires a callable
   * which is bound to a channel.
   */
  public ResponseT call(RequestT request) {
    return call(null, request);
  }

  /**
   * Convenience method to run a unary callable asynchronously. If no channel is provided,
   * the callable must be bound to one.
   */
  public void asyncCall(@Nullable Channel channel, RequestT request,
      StreamObserver<ResponseT> responseObserver) {
    ClientCalls.asyncUnaryCall(newCall(channel), request, responseObserver);
  }

  /**
   * Convenience method to run a unary callable asynchronously, without channel. Requires a callable
   * which is bound to a channel.
   */
  public void asyncCall(RequestT request, StreamObserver<ResponseT> responseObserver) {
    asyncCall(null, request, responseObserver);
  }

  /**
   * Convenience method to run a unary callable returning a future. If no channel is provided,
   * the callable must be bound to one.
   */
  public ListenableFuture<ResponseT> futureCall(@Nullable Channel channel, RequestT request) {
    return ClientCalls.futureUnaryCall(newCall(channel), request);
  }

  /**
   * Convenience method to run a unary callable returning a future, without a channel. Requires a
   * callable which is bound to a channel.
   */
  public ListenableFuture<ResponseT> futureCall(RequestT request) {
    return futureCall(null, request);
  }

  /**
   * Convenience method for a blocking server streaming call. If no channel is provided,
   * the callable must be bound to one.
   *
   * <p>Returns an iterable for the responses. Note the returned iterable can be used only once.
   * Returning an Iterator would be more precise, but iterators cannot be used in Java for loops.
   */
  public Iterable<ResponseT> iterableResponseStreamCall(@Nullable Channel channel,
      RequestT request) {
    final Iterator<ResponseT> result =
        ClientCalls.blockingServerStreamingCall(newCall(channel), request);
    return new Iterable<ResponseT>() {
      @Override
      public Iterator<ResponseT> iterator() {
        return result;
      }
    };
  }

  /**
   * Convenience method for a blocking server streaming call, without a channel. Requires a
   * callable which is bound to a channel.
   *
   * <p>Returns an iterable for the responses. Note the returned iterable can be used only once.
   * Returning an Iterator would be more precise, but iterators cannot be used in Java for loops.
   */
  public Iterable<ResponseT> iterableResponseStreamCall(RequestT request) {
    return iterableResponseStreamCall(null, request);
  }

  // Creation
  // ========

  /**
   * Returns a callable which executes the described method.
   *
   * <pre>
   *  Response response = Callable.create(SerivceGrpc.CONFIG.myMethod).call(channel, request);
   * </pre>
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      create(MethodDescriptor<RequestT, ResponseT> descriptor) {
    return create(CallableDescriptor.create(descriptor));
  }

  /**
   * Returns a callable which executes the method described by a {@link CallableDescriptor}.
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      create(final CallableDescriptor<RequestT, ResponseT> descriptor) {
    return new Callable<RequestT, ResponseT>() {
      @Override public ClientCall<RequestT, ResponseT> newCall(Channel channel) {
        if (channel == null) {
          throw new IllegalStateException(String.format(
              "unbound callable for method '%s' requires a channel for execution",
              descriptor.getMethodDescriptor().getFullMethodName()));
        }
        return channel.newCall(descriptor.getMethodDescriptor(), CallOptions.DEFAULT);
      }

      @Override public CallableDescriptor<RequestT, ResponseT> getDescriptor() {
        return descriptor;
      }

      @Override public String toString() {
        return descriptor.getMethodDescriptor().getFullMethodName();
      }
    };
  }

  /**
   * Returns a callable which executes the given function asynchronously on each provided
   * input. The supplied executor is used for creating tasks for each input. Example:
   *
   * <pre>
   *  Callable.Transformer&lt;RequestT, ResponseT> transformer = ...;
   *  Response response = Callable.create(transformer, executor).call(channel, request);
   * </pre>
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      create(Transformer<? super RequestT, ? extends ResponseT> transformer, Executor executor) {
    return new TransformingCallable<RequestT, ResponseT>(transformer, executor);
  }


  /**
   * Returns a callable which executes the given function immediately on each provided input.
   * Similar as {@link #create(Transformer, Executor)} but does not operate asynchronously and does
   * not require an executor.
   *
   * <p>Note that the callable returned by this method does not respect flow control. Some
   * operations applied to it may deadlock because of this. However, it is safe to use this
   * callable in the context of a {@link #followedBy(Callable)} operation, which is the major
   * use cases for transformers. But if you use a transformer to simulate a real rpc
   * you should use {@link #create(Transformer, Executor)} instead.
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      create(Transformer<? super RequestT, ? extends ResponseT> transformer) {
    return new TransformingCallable<RequestT, ResponseT>(transformer, null);
  }

  /**
   * Interface for a transformer. It can throw a {@link StatusException} to indicate an error.
   */
  public interface Transformer<RequestT, ResponseT> {
    ResponseT apply(RequestT request) throws StatusException;
  }

  /**
   * Returns a callable which echos its input.
   */
  public static <RequestT> Callable<RequestT, RequestT>
      identity() {
    return new TransformingCallable<RequestT, RequestT>(new Transformer<RequestT, RequestT>() {
      @Override public RequestT apply(RequestT request) throws StatusException {
        return request;
      }
    }, null);
  }

  /**
   * Returns a callable which always returns the given constant.
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      constant(final ResponseT result) {
    return new TransformingCallable<RequestT, ResponseT>(new Transformer<RequestT, ResponseT>() {
      @Override public ResponseT apply(RequestT request) throws StatusException {
        return result;
      }
    }, null);
  }

  // Followed-By
  // ===========

  /**
   * Returns a callable which forwards the responses from this callable as requests into the other
   * callable. Works both for unary and streaming operands. Example:
   *
   * <pre>
   * String bookName = ...;
   * Callable.Transformer&lt;Book, GetAuthorRequest> bookToGetAuthorRequest = ...;
   * Author response =
   *     Callable.create(LibraryGrpc.CONFIG.getBook)
   *             .followedBy(Callable.create(bookToGetAuthorRequest))
   *             .followedBy(Callable.create(LibraryGrpc.CONFIG.getAuthor))
   *             .call(channel, new GetBookRequest().setName(bookName).build());
   * </pre>
   *
   * <p>For streaming calls, each output of the first callable will be forwarded to the second
   * one as it arrives, allowing for streaming pipelines.
   */
  public <FinalResT> Callable<RequestT, FinalResT>
      followedBy(Callable<ResponseT, FinalResT> callable) {
    return new FollowedByCallable<RequestT, ResponseT, FinalResT>(this, callable);
  }

  // Channel Redirection
  // ===================

  /**
   * Returns a callable which redirects the execution of this callable to a channel determined at
   * call execution time, via a supplier. This can be used for creating batches which target
   * different services. Example:
   *
   * <pre>
   * Author response =
   *     Callable.redirect(
   *       Suppliers.ofInstance(bookChannel),
   *       Callable.create(LibraryGrpc.CONFIG.getBook))
   *     .followedBy(Callable.create(LibraryGrpc.CONFIG.getAuthor))
   *     .call(authorChannel, new GetBookRequest().setName(bookName));
   * </pre>
   */
  public static <RequestT, ResponseT> Callable<RequestT, ResponseT>
      redirect(final Supplier<Channel> channelSupplier,
               final Callable<RequestT, ResponseT> scoped) {
    return new Callable<RequestT, ResponseT>() {
      @Override
      public ClientCall<RequestT, ResponseT> newCall(Channel channel) {
        return scoped.newCall(channelSupplier.get());
      }
    };
  }

  // Retrying
  // ========

  /**
   * Returns a callable which retries using exponential back-off on transient errors. Example:
   *
   * <pre>
   * Response response = Callable.create(METHOD).retrying().call(channel, request);
   * </pre>
   *
   * <p>The call will be retried if and only if the returned status code is {@code UNAVAILABLE}.
   *
   * <p>No output will be produced until the underlying callable has succeeded. Applied to compound
   * callables, this can be used to implement simple transactions supposed the underlying callables
   * are either side-effect free or idempotent.
   *
   * <p>Note that the retry callable requires to buffer all inputs and outputs of the underlying
   * callable, and should be used with care when applied to streaming calls.
   */
  public Callable<RequestT, ResponseT> retrying() {
    return new RetryingCallable<RequestT, ResponseT>(this);
  }

  // Page Streaming
  // ==============

  /**
   * Returns a callable which streams the resources obtained from a series of calls to a method
   * implementing the pagination pattern. Example:
   *
   * <pre>
   *  for (Resource resource :
   *       Callable.create(listBooksDescriptor)
   *               .pageStreaming(pageDescriptor)
   *               .iterableResponseStreamCall(channel, request)) {
   *    doSomething(resource);
   *  }
   *</pre>
   *
   * <p>The returned stream does not buffer results; if it is traversed again, the API will be
   * called again.
   */
  public <ResourceT> Callable<RequestT, ResourceT>
      pageStreaming(PageDescriptor<RequestT, ResponseT, ResourceT> pageDescriptor) {
    return new PageStreamingCallable<RequestT, ResponseT, ResourceT>(this, pageDescriptor);
  }

  /**
   * Returns a callable which behaves the same as {@link #pageStreaming(PageDescriptor)}, with
   * the page descriptor attempted to derive from the callable descriptor.
   *
   * @throws IllegalArgumentException if a page descriptor is not derivable.
   */
  public <ResourceT> Callable<RequestT, ResourceT>
      pageStreaming(Class<ResourceT> resourceType) {
    PageDescriptor<RequestT, ResponseT, ResourceT> pageDescriptor =
        getDescriptor() != null ? getDescriptor().getPageDescriptor(resourceType) : null;
    if (pageDescriptor == null) {
      throw new IllegalArgumentException(String.format(
          "cannot derive page descriptor for '%s'", this));
    }
    return pageStreaming(pageDescriptor);
  }
}
