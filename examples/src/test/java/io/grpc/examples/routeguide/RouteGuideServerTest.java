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

import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;

/**
 * Unit tests for {@link RouteGuideServer}.
 * For demonstrating how to write gRPC unit test only.
 * Not intended to provide a high code coverage or to test every major usecase.
 *
 * <p>For basic unit test examples see {@link io.grpc.examples.helloworld.HelloWorldClientTest} and
 * {@link io.grpc.examples.helloworld.HelloWorldServerTest}.
 */
@RunWith(JUnit4.class)
public class RouteGuideServerTest {
  private RouteGuideServer server;
  private ManagedChannel inProcessChannel;
  private Collection<Feature> features;

  @Before
  public void setUp() throws IOException {
    String uniqueServerName = "fake server for " + this.getClass();
    features = new ArrayList<Feature>();
    server = new RouteGuideServer(InProcessServerBuilder.forName(uniqueServerName), 0, features);
    server.start();
    inProcessChannel = InProcessChannelBuilder.forName(uniqueServerName).build();
  }

  @After
  public void tearDown() throws InterruptedException {
    if (inProcessChannel != null) {
      inProcessChannel.shutdownNow();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testGetFeature() {
    Point point = Point.newBuilder().setLongitude(1).setLatitude(1).build();
    Feature unnamedFeature = Feature.newBuilder()
        .setName("").setLocation(point).build();
    RouteGuideGrpc.RouteGuideBlockingStub stub = RouteGuideGrpc.newBlockingStub(inProcessChannel);

    // feature not found in the server
    Feature feature = stub.getFeature(point);

    assertEquals(unnamedFeature, feature);

    // feature found in the server
    Feature namedFeature = Feature.newBuilder()
        .setName("name").setLocation(point).build();
    features.add(namedFeature);

    feature = stub.getFeature(point);

    assertEquals(namedFeature, feature);
  }

  @Test
  public void testListFeatures() throws InterruptedException {
    // setup
    Point lo = Point.newBuilder().setLongitude(0).setLatitude(0).build();
    Point hi = Point.newBuilder().setLongitude(10).setLatitude(10).build();
    Rectangle rect = Rectangle.newBuilder().setLo(lo).setHi(hi).build();
    Point p1 = Point.newBuilder().setLongitude(-1).setLatitude(-1).build();
    Point p2 = Point.newBuilder().setLongitude(2).setLatitude(2).build();
    Point p3 = Point.newBuilder().setLongitude(3).setLatitude(3).build();
    Point p4 = Point.newBuilder().setLongitude(4).setLatitude(4).build();
    Feature f1 = Feature.newBuilder().setLocation(p1).setName("f1").build(); // not inside rect
    Feature f2 = Feature.newBuilder().setLocation(p2).setName("f2").build();
    Feature f3 = Feature.newBuilder().setLocation(p3).setName("f3").build();
    Feature f4 = Feature.newBuilder().setLocation(p4).build(); // unamed
    features.add(f1);
    features.add(f2);
    features.add(f3);
    features.add(f4);
    final Collection<Feature> result = new HashSet<Feature>();
    final CountDownLatch latch = new CountDownLatch(1);
    StreamObserver<Feature> responseObserver =
        new StreamObserver<Feature>() {
          @Override
          public void onNext(Feature value) {
            result.add(value);
          }

          @Override
          public void onError(Throwable t) {
            fail();
          }

          @Override
          public void onCompleted() {
            latch.countDown();
          }
        };
    RouteGuideGrpc.RouteGuideStub stub = RouteGuideGrpc.newStub(inProcessChannel);

    // run
    stub.listFeatures(rect, responseObserver);
    latch.await();

    // verify
    Collection<Feature> expected = new HashSet<Feature>();
    expected.add(f2);
    expected.add(f3);
    assertEquals(expected, result);
  }

  @Test
  public void testRecordRoute() {
    Point p1 = Point.newBuilder().setLongitude(1).setLatitude(1).build();
    Point p2 = Point.newBuilder().setLongitude(2).setLatitude(2).build();
    Point p3 = Point.newBuilder().setLongitude(3).setLatitude(3).build();
    Point p4 = Point.newBuilder().setLongitude(4).setLatitude(4).build();
    int expectedDist = (int) (RouteGuideServer.calcDistance(p1, p2)
        + RouteGuideServer.calcDistance(p2, p3)
        + RouteGuideServer.calcDistance(p3, p4));
    Feature f1 = Feature.newBuilder().setLocation(p1).build(); // unamed
    Feature f2 = Feature.newBuilder().setLocation(p2).setName("f2").build();
    Feature f3 = Feature.newBuilder().setLocation(p3).setName("f3").build();
    Feature f4 = Feature.newBuilder().setLocation(p4).build(); // unamed
    features.add(f1);
    features.add(f2);
    features.add(f3);
    features.add(f4);

    StreamObserver<RouteSummary> responseObserver =
        (StreamObserver<RouteSummary>) mock(StreamObserver.class);
    RouteGuideGrpc.RouteGuideStub stub = RouteGuideGrpc.newStub(inProcessChannel);
    ArgumentCaptor<RouteSummary> routeSummaryCaptor = ArgumentCaptor.forClass(RouteSummary.class);

    StreamObserver<Point> requestObserver = stub.recordRoute(responseObserver);

    requestObserver.onNext(p1);
    requestObserver.onNext(p2);
    requestObserver.onNext(p3);
    requestObserver.onNext(p4);

    verify(responseObserver, never())
        .onNext(any(RouteSummary.class));

    requestObserver.onCompleted();

    verify(responseObserver, timeout(100).times(1))
        .onNext(routeSummaryCaptor.capture());
    RouteSummary summary = routeSummaryCaptor.getValue();
    assertEquals(expectedDist, summary.getDistance());
    assertEquals(2, summary.getFeatureCount());
    verify(responseObserver, timeout(100).times(1))
        .onCompleted();
    verify(responseObserver, never())
        .onError(any(Throwable.class));
  }

  @Test
  public void testRouteChat() {
    Point p1 = Point.newBuilder().setLongitude(1).setLatitude(1).build();
    Point p2 = Point.newBuilder().setLongitude(2).setLatitude(2).build();
    RouteNote n1 = RouteNote.newBuilder().setLocation(p1).setMessage("m1").build();
    RouteNote n2 = RouteNote.newBuilder().setLocation(p2).setMessage("m2").build();
    RouteNote n3 = RouteNote.newBuilder().setLocation(p1).setMessage("m3").build();
    RouteNote n4 = RouteNote.newBuilder().setLocation(p2).setMessage("m4").build();
    RouteNote n5 = RouteNote.newBuilder().setLocation(p1).setMessage("m5").build();
    RouteNote n6 = RouteNote.newBuilder().setLocation(p1).setMessage("m6").build();
    int timesOnNext = 0;

    StreamObserver<RouteNote> responseObserver =
        (StreamObserver<RouteNote>) mock(StreamObserver.class);
    RouteGuideGrpc.RouteGuideStub stub = RouteGuideGrpc.newStub(inProcessChannel);

    StreamObserver<RouteNote> requestObserver = stub.routeChat(responseObserver);
    verify(responseObserver, never())
        .onNext(any(RouteNote.class));

    requestObserver.onNext(n1);
    verify(responseObserver, never())
        .onNext(any(RouteNote.class));

    requestObserver.onNext(n2);
    verify(responseObserver, never())
        .onNext(any(RouteNote.class));

    requestObserver.onNext(n3);
    ArgumentCaptor<RouteNote> routeNoteCaptor = ArgumentCaptor.forClass(RouteNote.class);
    verify(responseObserver, timeout(100).times(++timesOnNext))
        .onNext(routeNoteCaptor.capture());
    RouteNote result = routeNoteCaptor.getValue();
    assertEquals(p1, result.getLocation());
    assertEquals("m1", result.getMessage());

    requestObserver.onNext(n4);
    routeNoteCaptor = ArgumentCaptor.forClass(RouteNote.class);
    verify(responseObserver, timeout(100).times(++timesOnNext))
        .onNext(routeNoteCaptor.capture());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 1);
    assertEquals(p2, result.getLocation());
    assertEquals("m2", result.getMessage());

    requestObserver.onNext(n5);
    routeNoteCaptor = ArgumentCaptor.forClass(RouteNote.class);
    timesOnNext += 2;
    verify(responseObserver, timeout(100).times(timesOnNext))
        .onNext(routeNoteCaptor.capture());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 2);
    assertEquals(p1, result.getLocation());
    assertEquals("m1", result.getMessage());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 1);
    assertEquals(p1, result.getLocation());
    assertEquals("m3", result.getMessage());

    requestObserver.onNext(n6);
    routeNoteCaptor = ArgumentCaptor.forClass(RouteNote.class);
    timesOnNext += 3;
    verify(responseObserver, timeout(100).times(timesOnNext))
        .onNext(routeNoteCaptor.capture());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 3);
    assertEquals(p1, result.getLocation());
    assertEquals("m1", result.getMessage());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 2);
    assertEquals(p1, result.getLocation());
    assertEquals("m3", result.getMessage());
    result = routeNoteCaptor.getAllValues().get(timesOnNext - 1);
    assertEquals(p1, result.getLocation());
    assertEquals("m5", result.getMessage());

    requestObserver.onCompleted();
    verify(responseObserver, timeout(100).times(1))
        .onCompleted();
    verify(responseObserver, never())
        .onError(any(Throwable.class));
  }
}
