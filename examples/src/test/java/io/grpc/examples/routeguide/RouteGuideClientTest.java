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

package io.grpc.examples.routeguide;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Unit tests for {@link RouteGuideClient}.
 * For demonstrating how to write gRPC unit test only.
 * Not intended to provide a high code coverage or to test every major usecase.
 *
 * <p>For basic unit test examples see {@link io.grpc.examples.helloworld.HelloWorldClientTest} and
 * {@link io.grpc.examples.helloworld.HelloWorldServerTest}.
 */
@RunWith(JUnit4.class)
public class RouteGuideClientTest {
  private final RouteGuideGrpc.RouteGuideImplBase serviceImpl =
      spy(new RouteGuideGrpc.RouteGuideImplBase() {});

  private Server fakeServer;
  private RouteGuideClient client;

  @Before
  public void setUp() throws IOException {
    String uniqueServerName = "fake server for " + getClass();
    fakeServer =
        InProcessServerBuilder.forName(uniqueServerName).addService(serviceImpl).build().start();
    ManagedChannelBuilder channelBuilder = InProcessChannelBuilder.forName(uniqueServerName);
    client = spy(
        new RouteGuideClient(channelBuilder) {
          int idx = 0;
          @Override
          int randomIndex(int range, Random random) {
            // deterministic and incremental
            return idx++ % range;
          }

          @Override
          void sleep(long millis) {
            // no sleep
          }
        });
  }

  @After
  public void tearDown() throws InterruptedException {
    if (client != null) {
      client.shutdown();
    }
    if (fakeServer != null) {
      fakeServer.shutdownNow();
    }
  }

  /**
   * Example for testing blocking unary call.
   */
  @Test
  public void testGetFeature_exist() {
    Point requestPoint =  Point.newBuilder().setLatitude(-1).setLongitude(-1).build();
    Point responsePoint = Point.newBuilder().setLatitude(-123).setLongitude(-123).build();
    final Feature responseFeature =
        Feature.newBuilder().setName("dummyFeature").setLocation(responsePoint).build();
    Answer<Void> answer =
        new Answer<Void>() {
          @SuppressWarnings("unchecked")
          @Override
          public Void answer(InvocationOnMock invocation) {
            StreamObserver<Feature> responseObserver =
                (StreamObserver<Feature>) invocation.getArguments()[1];
            responseObserver.onNext(responseFeature);
            responseObserver.onCompleted();
            return null;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .getFeature(any(Point.class), Matchers.<StreamObserver<Feature>>any());

    client.getFeature(-1, -1);

    verify(serviceImpl, times(1))
        .getFeature(eq(requestPoint), Matchers.<StreamObserver<Feature>>any());
    verify(client, times(1))
        .info(
            "Found feature called \"{0}\" at {1}, {2}",
            "dummyFeature",
            RouteGuideUtil.getLatitude(responseFeature.getLocation()),
            RouteGuideUtil.getLongitude(responseFeature.getLocation()));
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing blocking unary call.
   */
  @Test
  public void testGetFeature_notExist() {
    Point requestPoint =  Point.newBuilder().setLatitude(-1).setLongitude(-1).build();
    Point responsePoint = Point.newBuilder().setLatitude(-123).setLongitude(-123).build();
    final Feature responseFeature =
        Feature.newBuilder().setLocation(responsePoint).build();
    Answer<Void> answer =
        new Answer<Void>() {
          @SuppressWarnings("unchecked")
          @Override
          public Void answer(InvocationOnMock invocation) {
            StreamObserver<Feature> responseObserver =
                (StreamObserver<Feature>) invocation.getArguments()[1];
            responseObserver.onNext(responseFeature);
            responseObserver.onCompleted();
            return null;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .getFeature(any(Point.class), Matchers.<StreamObserver<Feature>>any());

    client.getFeature(-1, -1);

    verify(serviceImpl, times(1))
        .getFeature(eq(requestPoint), Matchers.<StreamObserver<Feature>>any());
    verify(client, times(1))
        .info(
            "Found no feature at {0}, {1}",
            RouteGuideUtil.getLatitude(responseFeature.getLocation()),
            RouteGuideUtil.getLongitude(responseFeature.getLocation()));
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing blocking unary call.
   */
  @Test
  public void testGetFeature_error() {
    Point requestPoint =  Point.newBuilder().setLatitude(-1).setLongitude(-1).build();
    Answer<Void> answer =
        new Answer<Void>() {
          @SuppressWarnings("unchecked")
          @Override
          public Void answer(InvocationOnMock invocation) {
            StreamObserver<Feature> responseObserver =
                (StreamObserver<Feature>) invocation.getArguments()[1];
            responseObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));
            return null;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .getFeature(any(Point.class), Matchers.<StreamObserver<Feature>>any());

    client.getFeature(-1, -1);

    verify(serviceImpl, times(1))
        .getFeature(eq(requestPoint), Matchers.<StreamObserver<Feature>>any());
    verify(client, times(1))
        .warning(
            "RPC failed: {0}",
            Status.DATA_LOSS);
    verify(client, never())
        .info(
            eq("Found no feature at {0}, {1}"),
            any(Object.class),
            any(Object.class));
    verify(client, never())
        .info(
            eq("Found feature called \"{0}\" at {1}, {2}"),
            any(Object.class),
            any(Object.class));
  }

  /**
   * Example for testing blocking server-streaming.
   */
  @Test
  public void testListFeatures() {
    final Feature responseFeature1 =
        Feature.newBuilder().setName("feature 1").build();
    final Feature responseFeature2 =
        Feature.newBuilder().setName("feature 2").build();
    Answer<Void> answer =
        new Answer<Void>() {
          @SuppressWarnings("unchecked")
          @Override
          public Void answer(InvocationOnMock invocation) {
            StreamObserver<Feature> responseObserver =
                (StreamObserver<Feature>) invocation.getArguments()[1];

            // before sending any response,
            // verify the client#listFeatures method has been called (but has not returned yet)
            verify(client, times(1))
                .listFeatures(1, 2, 3, 4);
            verify(client, times(1))
                .info("*** ListFeatures: lowLat={0} lowLon={1} hiLat={2} hiLon={3}", 1, 2, 3, 4);
            verify(client, never())
                .info(any(String.class), any(Object[].class));

            // send one response message
            responseObserver.onNext(responseFeature1);

            verify(client, timeout(100).times(1))
                .info("Result #1: {0}", responseFeature1);
            verify(client, never())
                .info("Result #2: {0}", responseFeature2);

            // send another response message
            responseObserver.onNext(responseFeature2);

            verify(client, timeout(100).times(1))
                .info("Result #2: {0}", responseFeature2);

            // complete the response
            responseObserver.onCompleted();

            return null;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .listFeatures(any(Rectangle.class), Matchers.<StreamObserver<Feature>>any());

    client.listFeatures(1, 2, 3, 4);

    verify(serviceImpl, times(1))
        .listFeatures(any(Rectangle.class), Matchers.<StreamObserver<Feature>>any());
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing blocking server-streaming.
   */
  @Test
  public void testListFeatures_error() {
    final Feature responseFeature1 =
        Feature.newBuilder().setName("feature 1").build();
    Answer<Void> answer =
        new Answer<Void>() {
          @SuppressWarnings("unchecked")
          @Override
          public Void answer(InvocationOnMock invocation) {
            StreamObserver<Feature> responseObserver =
                (StreamObserver<Feature>) invocation.getArguments()[1];

            // before sending response
            verify(client, times(1))
                .listFeatures(1, 2, 3, 4);
            verify(client, times(1))
                .info("*** ListFeatures: lowLat={0} lowLon={1} hiLat={2} hiLon={3}", 1, 2, 3, 4);
            verify(client, never())
                .info(any(String.class), any(Object[].class));
            verify(client, never())
                .warning(any(String.class), any(Status.class));

            // send one response message
            responseObserver.onNext(responseFeature1);

            verify(client, timeout(100).times(1))
                .info("Result #1: {0}", responseFeature1);
            verify(client, never())
                .warning(any(String.class), any(Status.class));

            // let the rpc fail
            responseObserver.onError(new StatusRuntimeException(Status.CANCELLED));
            return null;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .listFeatures(any(Rectangle.class), Matchers.<StreamObserver<Feature>>any());

    client.listFeatures(1, 2, 3, 4);

    verify(client, times(1))
        .warning("RPC failed: {0}", Status.CANCELLED);
  }

  /**
   * Example for testing async client-streaming.
   */
  @Test
  public void testRecordRoute() throws InterruptedException {

    Point point1 = Point.newBuilder().setLatitude(1).setLongitude(1).build();
    Point point2 = Point.newBuilder().setLatitude(2).setLongitude(2).build();
    Point point3 = Point.newBuilder().setLatitude(3).setLongitude(3).build();
    Feature requestFeature1 =
        Feature.newBuilder().setLocation(point1).build();
    Feature requestFeature2 =
        Feature.newBuilder().setLocation(point2).build();
    Feature requestFeature3 =
        Feature.newBuilder().setLocation(point3).build();
    final List<Feature> features = Arrays.asList(
        requestFeature1, requestFeature2, requestFeature3);
    final RouteSummary fakeResponse = RouteSummary
        .newBuilder()
        .setPointCount(7)
        .setFeatureCount(8)
        .setDistance(9)
        .setElapsedTime(10)
        .build();
    Answer<StreamObserver<Point>> answer =
        new Answer<StreamObserver<Point>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<Point> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).recordRoute(eq(features), eq(4));

            final StreamObserver<RouteSummary> responseObserver =
                (StreamObserver<RouteSummary>) invocation.getArguments()[0];

            StreamObserver<Point> requestObserver = new StreamObserver<Point>() {
              int idx = 0;

              @Override
              public void onNext(Point value) {
                assertEquals(features.get(idx++ % 3).getLocation(), value);
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onNext(fakeResponse);
                responseObserver.onCompleted();
              }
            };

            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .recordRoute(Matchers.<StreamObserver<RouteSummary>>any());

    // send requestFeature1, requestFeature2, requestFeature3, and then requestFeature1 again.
    client.recordRoute(features, 4);

    verify(client, times(1))
        .info("*** RecordRoute");
    verify(client, timeout(100).times(2))
        .info(
            eq("Visiting point {0}, {1}"),
            eq(RouteGuideUtil.getLatitude(requestFeature1.getLocation())),
            eq(RouteGuideUtil.getLongitude(requestFeature1.getLocation())));
    verify(client, timeout(100).times(1))
        .info(
            eq("Visiting point {0}, {1}"),
            eq(RouteGuideUtil.getLatitude(requestFeature2.getLocation())),
            eq(RouteGuideUtil.getLongitude(requestFeature2.getLocation())));
    verify(client, timeout(100).times(1))
        .info(
            eq("Visiting point {0}, {1}"),
            eq(RouteGuideUtil.getLatitude(requestFeature3.getLocation())),
            eq(RouteGuideUtil.getLongitude(requestFeature3.getLocation())));
    verify(client, timeout(100).times(1))
        .info(
            "Finished trip with {0} points. Passed {1} features. "
                + "Travelled {2} meters. It took {3} seconds.",
            7, 8, 9, 10);
    verify(client, timeout(100).times(1))
        .info("Finished RecordRoute");
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing async client-streaming.
   */
  @Test
  public void testRecordRoute_wrongResponse() throws InterruptedException {
    Point point1 = Point.newBuilder().setLatitude(1).setLongitude(1).build();
    final Feature requestFeature1 =
        Feature.newBuilder().setLocation(point1).build();
    final List<Feature> features = Arrays.asList(requestFeature1);

    Answer<StreamObserver<Point>> answer =
        new Answer<StreamObserver<Point>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<Point> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).recordRoute(eq(features), eq(4));

            StreamObserver<RouteSummary> responseObserver =
                (StreamObserver<RouteSummary>) invocation.getArguments()[0];
            RouteSummary response = RouteSummary.getDefaultInstance();
            // sending more than one responses is not right for client-streaming call.
            responseObserver.onNext(response);
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            StreamObserver<Point> requestObserver = new StreamObserver<Point>() {
              @Override
              public void onNext(Point value) {
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
              }
            };
            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .recordRoute(Matchers.<StreamObserver<RouteSummary>>any());

    client.recordRoute(features, 4);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(client, timeout(100).times(1))
        .warning(eq("RecordRoute Failed: {0}"), statusCaptor.capture());
    assertEquals(Code.CANCELLED, statusCaptor.getValue().getCode());
    verify(client, never())
        .info("Finished RecordRoute");
  }

  /**
   * Example for testing async client-streaming.
   */
  @Test
  public void testRecordRoute_serverError() throws InterruptedException {
    Point point1 = Point.newBuilder().setLatitude(1).setLongitude(1).build();
    final Feature requestFeature1 =
        Feature.newBuilder().setLocation(point1).build();
    final List<Feature> features = Arrays.asList(requestFeature1);

    Answer<StreamObserver<Point>> answer =
        new Answer<StreamObserver<Point>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<Point> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).recordRoute(eq(features), eq(4));

            StreamObserver<RouteSummary> responseObserver =
                (StreamObserver<RouteSummary>) invocation.getArguments()[0];
            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL));

            StreamObserver<Point> requestObserver = new StreamObserver<Point>() {
              @Override
              public void onNext(Point value) {
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
              }
            };
            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .recordRoute(Matchers.<StreamObserver<RouteSummary>>any());

    client.recordRoute(features, 4);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(client, timeout(100).times(1))
        .warning(eq("RecordRoute Failed: {0}"), statusCaptor.capture());
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
    verify(client, never())
        .info("Finished RecordRoute");
  }

  /**
   * Example for testing async client-streaming.
   */
  @Test
  public void testRecordRoute_erroneousServiceImpl() throws InterruptedException {
    Point point1 = Point.newBuilder().setLatitude(1).setLongitude(1).build();
    final Feature requestFeature1 =
        Feature.newBuilder().setLocation(point1).build();
    final List<Feature> features = Arrays.asList(requestFeature1);

    Answer<StreamObserver<Point>> answer =
        new Answer<StreamObserver<Point>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<Point> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).recordRoute(eq(features), eq(4));

            StreamObserver<Point> requestObserver = new StreamObserver<Point>() {
              @Override
              public void onNext(Point value) {
                throw new RuntimeException(
                    "unexpected error due to careless implementation of serviceImpl");
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
              }
            };
            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .recordRoute(Matchers.<StreamObserver<RouteSummary>>any());

    client.recordRoute(features, 4);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(client, timeout(100).times(1))
        .warning(eq("RecordRoute Failed: {0}"), statusCaptor.capture());
    assertEquals(Code.UNKNOWN, statusCaptor.getValue().getCode());
    verify(client, never())
        .info("Finished RecordRoute");
  }

  /**
   * Example for testing bi-directional call.
   */
  @Test
  public void testRouteChat_simpleResponse() throws InterruptedException {
    final RouteNote fakeResponse = RouteNote.newBuilder().setMessage("dummy msg").build();
    Answer<StreamObserver<RouteNote>> answer =
        new Answer<StreamObserver<RouteNote>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<RouteNote> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).routeChat();

            final StreamObserver<RouteNote> responseObserver =
                (StreamObserver<RouteNote>) invocation.getArguments()[0];

            // send out two simple response message
            responseObserver.onNext(fakeResponse);
            responseObserver.onNext(fakeResponse);

            StreamObserver<RouteNote> requestObserver = new StreamObserver<RouteNote>() {
              int idx = 0;
              final String[] messages =
                  {"First message", "Second message", "Third message", "Fourth message"};

              @Override
              public void onNext(RouteNote value) {
                assertEquals(messages[idx++], value.getMessage());
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };

            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .routeChat(Matchers.<StreamObserver<RouteNote>>any());

    client.routeChat();

    verify(client, times(1))
        .info("*** RouteChat");
    // request message sent for four times
    verify(client, timeout(100).times(4))
        .info(
            eq("Sending message \"{0}\" at {1}, {2}"),
            any(Object.class),
            any(Object.class),
            any(Object.class));
    // response message received for two times
    verify(client, timeout(100).times(2))
        .info(
            eq("Got message \"{0}\" at {1}, {2}"),
            eq("dummy msg"),
            any(Object.class),
            any(Object.class));
    verify(client, timeout(100).times(1))
        .info("Finished RouteChat");
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing bi-directional call.
   */
  @Test
  public void testRouteChat_echoResponse() throws InterruptedException {
    Answer<StreamObserver<RouteNote>> answer =
        new Answer<StreamObserver<RouteNote>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<RouteNote> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).routeChat();

            final StreamObserver<RouteNote> responseObserver =
                (StreamObserver<RouteNote>) invocation.getArguments()[0];

            StreamObserver<RouteNote> requestObserver = new StreamObserver<RouteNote>() {
              @Override
              public void onNext(RouteNote value) {
                responseObserver.onNext(value);
              }

              @Override
              public void onError(Throwable t) {
                responseObserver.onError(t);
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };

            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .routeChat(Matchers.<StreamObserver<RouteNote>>any());

    client.routeChat();

    verify(client, times(1)).info("*** RouteChat");
    String[] messages =
        {"First message", "Second message", "Third message", "Fourth message"};
    for (int i = 0; i < 4; i++) {
      verify(client, timeout(100).times(1))
          .info(
              eq("Sending message \"{0}\" at {1}, {2}"),
              eq(messages[i]),
              any(Object.class),
              any(Object.class));
      verify(client, timeout(100).times(1))
          .info(
              eq("Got message \"{0}\" at {1}, {2}"),
              eq(messages[i]),
              any(Object.class),
              any(Object.class));
    }
    verify(client, timeout(100).times(1))
        .info("Finished RouteChat");
    verify(client, never())
        .warning(any(String.class), any(Status.class));
  }

  /**
   * Example for testing bi-directional call.
   */
  @Test
  public void testRouteChat_errorResponse() throws InterruptedException {
    Answer<StreamObserver<RouteNote>> answer =
        new Answer<StreamObserver<RouteNote>>() {
          @SuppressWarnings("unchecked")
          @Override
          public StreamObserver<RouteNote> answer(InvocationOnMock invocation) throws Throwable {
            // before sending response
            verify(client, times(1)).routeChat();

            final StreamObserver<RouteNote> responseObserver =
                (StreamObserver<RouteNote>) invocation.getArguments()[0];

            StreamObserver<RouteNote> requestObserver = new StreamObserver<RouteNote>() {
              @Override
              public void onNext(RouteNote value) {
                responseObserver.onError(new StatusRuntimeException(Status.PERMISSION_DENIED));
              }

              @Override
              public void onError(Throwable t) {
              }

              @Override
              public void onCompleted() {
                responseObserver.onCompleted();
              }
            };

            return requestObserver;
          }
        };
    doAnswer(answer)
        .when(serviceImpl)
        .routeChat(Matchers.<StreamObserver<RouteNote>>any());

    client.routeChat();

    verify(client, times(1)).info("*** RouteChat");
    String[] messages =
        {"First message", "Second message", "Third message", "Fourth message"};
    for (int i = 0; i < 4; i++) {
      verify(client, timeout(100).times(1))
          .info(
              eq("Sending message \"{0}\" at {1}, {2}"),
              eq(messages[i]),
              any(Object.class),
              any(Object.class));
    }
    verify(client, timeout(100).times(1))
        .warning(eq("RouteChat Failed: {0}"), eq(Status.PERMISSION_DENIED));
    verify(client, never())
        .info(
            eq("Got message \"{0}\" at {1}, {2}"),
            any(Object.class),
            any(Object.class),
            any(Object.class));
    verify(client, never())
        .info("Finished RouteChat");
  }
}
