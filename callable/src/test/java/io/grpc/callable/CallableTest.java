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

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.truth.Truth;

import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.callable.Callable.Transformer;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * Tests for {@link Callable}.
 */
@RunWith(JUnit4.class)
public class CallableTest {

  private static final Transformer<Integer, Integer> PLUS_ONE =
      new Transformer<Integer, Integer>() {
        @Override public Integer apply(Integer request) throws StatusException {
          return request + 1;
        }
      };

  private static final Transformer<Object, String> TO_STRING =
      new Transformer<Object, String>() {
        @Override public String apply(Object request) throws StatusException {
          return request.toString();
        }
      };

  private static final Transformer<String, Integer> TO_INT =
      new Transformer<String, Integer>() {
        @Override public Integer apply(String request) throws StatusException {
          return Integer.parseInt(request);
        }
      };

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Mock Channel channel;

  // Creation and Chaining
  // =====================

  @Mock StreamObserver<Integer> output;
  @Mock Transformer<Integer, Integer> transformIntToInt;

  @Test public void transformAndFollowedBy() {
    Callable<Object, Integer> callable =
        Callable.create(TO_STRING)
                .followedBy(Callable.create(TO_INT))
                .followedBy(Callable.create(PLUS_ONE));

    // Unary call
    Truth.assertThat(callable.call(channel, 1)).isEqualTo(2);

    // Streaming call
    StreamObserver<Object> input =
        ClientCalls.asyncBidiStreamingCall(callable.newCall(channel), output);
    input.onNext(1);
    input.onNext(2);
    input.onNext(3);
    input.onCompleted();

    InOrder inOrder = Mockito.inOrder(output);
    inOrder.verify(output).onNext(2);
    inOrder.verify(output).onNext(3);
    inOrder.verify(output).onNext(4);
  }

  // Retry
  // =====

  @Test public void retry() throws StatusException {
    Mockito.when(transformIntToInt.apply(Mockito.anyInt()))
        .thenThrow(new StatusException(Status.UNAVAILABLE))
        .thenThrow(new StatusException(Status.UNAVAILABLE))
        .thenThrow(new StatusException(Status.UNAVAILABLE))
        .thenReturn(2);
    Callable<Integer, Integer> callable = Callable.create(transformIntToInt).retrying();
    Truth.assertThat(callable.call(channel, 1)).isEqualTo(2);
  }


  // Page Streaming
  // ==============

  /**
   * Request message.
   */
  private static class Request {
    String pageToken;
  }

  /**
   * Response message.
   */
  private static class Response {
    String nextPageToken;
    List<String> books;
  }

  /**
   * A page producer fake which uses a seeded random generator to produce different page
   * sizes.
   */
  private static class PageProducer implements Transformer<Request, Response>  {
    List<String> collection;
    Random random = new Random(0);

    PageProducer() {
      collection = new ArrayList<String>();
      for (int i = 1; i < 20; i++) {
        collection.add("book #" + i);
      }
    }

    @Override
    public Response apply(Request request) {
      int start = 0;
      if (!Strings.isNullOrEmpty(request.pageToken)) {
        start = Integer.parseInt(request.pageToken);
      }
      int end = start + random.nextInt(3);
      String nextToken;
      if (end >= collection.size()) {
        end = collection.size();
        nextToken = "";
      } else {
        nextToken = end + "";
      }
      Response response = new Response();
      response.nextPageToken = nextToken;
      response.books = collection.subList(start, end);
      return response;
    }
  }

  private static class BooksPageDescriptor implements PageDescriptor<Request, Response, String> {

    @Override
    public Object emptyToken() {
      return "";
    }

    @Override
    public Request injectToken(Request payload, Object token) {
      payload.pageToken = (String) token;
      return payload;
    }

    @Override
    public Object extractNextToken(Response payload) {
      return payload.nextPageToken;
    }

    @Override
    public Iterable<String> extractResources(Response payload) {
      return payload.books;
    }
  }

  @Test public void pageStreaming() {

    // Create a callable.
    PageProducer producer = new PageProducer();
    Callable<Request, String> callable =
        Callable.create(producer, Executors.newCachedThreadPool())
                .pageStreaming(new BooksPageDescriptor());

    // Emit the call and check the result.
    Truth.assertThat(Lists.newArrayList(
        callable.iterableResponseStreamCall(channel, new Request())))
      .isEqualTo(producer.collection);
  }

  // Binding
  // =======

  @Mock
  MethodDescriptor<Request, Response> method;

  @Test public void testUnboundFailure() {
    Mockito.stub(method.getFullMethodName()).toReturn("mocked method");
    thrown.expectMessage(
        "unbound callable for method 'mocked method' requires "
        + "a channel for execution");

    Callable<Request, Response> callable = Callable.create(method);
    callable.call(new Request());
  }
}
