/*
 * Copyright 2014 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Lists;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.GrpcUtil.Http2Error;
import io.grpc.testing.TestMethodDescriptors;
import java.util.ArrayList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link GrpcUtil}. */
@RunWith(JUnit4.class)
public class GrpcUtilTest {

  private static final ClientStreamTracer[] tracers = new ClientStreamTracer[] {
      new ClientStreamTracer() {}
  };

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Captor
  private ArgumentCaptor<Status> statusCaptor;


  @Test
  public void http2ErrorForCode() {
    // Try edge cases manually, to make the test obviously correct for important cases.
    assertNull(Http2Error.forCode(-1));
    assertSame(Http2Error.NO_ERROR, Http2Error.forCode(0));
    assertSame(Http2Error.HTTP_1_1_REQUIRED, Http2Error.forCode(0xD));
    assertNull(Http2Error.forCode(0xD + 1));
  }

  @Test
  public void http2ErrorRoundTrip() {
    for (Http2Error error : Http2Error.values()) {
      assertSame(error, Http2Error.forCode(error.code()));
    }
  }

  @Test
  public void http2ErrorStatus() {
    // Nothing special about this particular error, except that it is slightly distinctive.
    assertSame(Status.Code.CANCELLED, Http2Error.CANCEL.status().getCode());
  }

  @Test
  public void http2ErrorStatusForCode() {
    assertSame(Status.Code.INTERNAL, Http2Error.statusForCode(-1).getCode());
    assertSame(Http2Error.NO_ERROR.status(), Http2Error.statusForCode(0));
    assertSame(Http2Error.HTTP_1_1_REQUIRED.status(), Http2Error.statusForCode(0xD));
    assertSame(Status.Code.INTERNAL, Http2Error.statusForCode(0xD + 1).getCode());
  }

  @Test
  public void timeoutTest() {
    GrpcUtil.TimeoutMarshaller marshaller =
            new GrpcUtil.TimeoutMarshaller();
    // nanos
    assertEquals("0n", marshaller.toAsciiString(0L));
    assertEquals(0L, (long) marshaller.parseAsciiString("0n"));

    assertEquals("99999999n", marshaller.toAsciiString(99999999L));
    assertEquals(99999999L, (long) marshaller.parseAsciiString("99999999n"));

    // micros
    assertEquals("100000u", marshaller.toAsciiString(100000000L));
    assertEquals(100000000L, (long) marshaller.parseAsciiString("100000u"));

    assertEquals("99999999u", marshaller.toAsciiString(99999999999L));
    assertEquals(99999999000L, (long) marshaller.parseAsciiString("99999999u"));

    // millis
    assertEquals("100000m", marshaller.toAsciiString(100000000000L));
    assertEquals(100000000000L, (long) marshaller.parseAsciiString("100000m"));

    assertEquals("99999999m", marshaller.toAsciiString(99999999999999L));
    assertEquals(99999999000000L, (long) marshaller.parseAsciiString("99999999m"));

    // seconds
    assertEquals("100000S", marshaller.toAsciiString(100000000000000L));
    assertEquals(100000000000000L, (long) marshaller.parseAsciiString("100000S"));

    assertEquals("99999999S", marshaller.toAsciiString(99999999999999999L));
    assertEquals(99999999000000000L, (long) marshaller.parseAsciiString("99999999S"));

    // minutes
    assertEquals("1666666M", marshaller.toAsciiString(100000000000000000L));
    assertEquals(99999960000000000L, (long) marshaller.parseAsciiString("1666666M"));

    assertEquals("99999999M", marshaller.toAsciiString(5999999999999999999L));
    assertEquals(5999999940000000000L, (long) marshaller.parseAsciiString("99999999M"));

    // hours
    assertEquals("1666666H", marshaller.toAsciiString(6000000000000000000L));
    assertEquals(5999997600000000000L, (long) marshaller.parseAsciiString("1666666H"));

    assertEquals("2562047H", marshaller.toAsciiString(Long.MAX_VALUE));
    assertEquals(9223369200000000000L, (long) marshaller.parseAsciiString("2562047H"));

    assertEquals(Long.MAX_VALUE, (long) marshaller.parseAsciiString("2562048H"));
  }

  @Test
  public void grpcUserAgent() {
    assertTrue(GrpcUtil.getGrpcUserAgent("netty", null).startsWith("grpc-java-netty/"));
    assertTrue(GrpcUtil.getGrpcUserAgent("okhttp", "libfoo/1.0")
        .startsWith("libfoo/1.0 grpc-java-okhttp"));
  }

  @Test
  public void contentTypeShouldBeValid() {
    assertTrue(GrpcUtil.isGrpcContentType(GrpcUtil.CONTENT_TYPE_GRPC));
    assertTrue(GrpcUtil.isGrpcContentType(GrpcUtil.CONTENT_TYPE_GRPC + "+blaa"));
    assertTrue(GrpcUtil.isGrpcContentType(GrpcUtil.CONTENT_TYPE_GRPC + ";blaa"));
  }

  @Test
  public void contentTypeShouldNotBeValid() {
    assertFalse(GrpcUtil.isGrpcContentType("application/bad"));
  }

  @Test
  public void urlAuthorityEscape_ipv6Address() {
    assertEquals("[::1]", GrpcUtil.AuthorityEscaper.encodeAuthority("[::1]"));
  }

  @Test
  public void urlAuthorityEscape_userInAuthority() {
    assertEquals("user@host", GrpcUtil.AuthorityEscaper.encodeAuthority("user@host"));
  }

  @Test
  public void urlAuthorityEscape_slashesAreEncoded() {
    assertEquals(
        "project%2F123%2Fnetwork%2Fabc%2Fservice",
        GrpcUtil.AuthorityEscaper.encodeAuthority("project/123/network/abc/service"));
  }

  @Test
  public void urlAuthorityEscape_allowedCharsAreNotEncoded() {
    assertEquals(
        "-._~!$&'()*+,;=@:[]", GrpcUtil.AuthorityEscaper.encodeAuthority("-._~!$&'()*+,;=@:[]"));
  }

  @Test
  public void urlAuthorityEscape_allLettersAndNumbers() {
    assertEquals(
        "abcdefghijklmnopqrstuvwxyz0123456789",
        GrpcUtil.AuthorityEscaper.encodeAuthority("abcdefghijklmnopqrstuvwxyz0123456789"));
  }

  @Test
  public void urlAuthorityEscape_unicodeAreNotEncoded() {
    assertEquals(
        "ö®", GrpcUtil.AuthorityEscaper.encodeAuthority("ö®"));
  }

  @Test
  public void checkAuthority_failsOnNull() {
    assertThrows(NullPointerException.class, () -> GrpcUtil.checkAuthority(null));
  }

  @Test
  public void checkAuthority_succeedsOnHostAndPort() {
    String actual = GrpcUtil.checkAuthority("valid:1234");

    assertEquals("valid:1234", actual);
  }

  @Test
  public void checkAuthority_succeedsOnHost() {
    String actual = GrpcUtil.checkAuthority("valid");

    assertEquals("valid", actual);
  }

  @Test
  public void checkAuthority_succeedsOnIpV6() {
    String actual = GrpcUtil.checkAuthority("[::1]");

    assertEquals("[::1]", actual);
  }

  @Test
  public void checkAuthority_failsOnInvalidAuthority() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> GrpcUtil.checkAuthority("[ : : 1]"));
    assertThat(e).hasMessageThat().isEqualTo("Invalid authority: [ : : 1]");
  }


  @Test
  public void checkAuthority_userInfoNotAllowed() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> GrpcUtil.checkAuthority("foo@valid"));
    assertThat(e).hasMessageThat()
        .isEqualTo("Userinfo must not be present on authority: 'foo@valid'");
  }

  @Test
  public void httpStatusToGrpcStatus_messageContainsHttpStatus() {
    assertTrue(GrpcUtil.httpStatusToGrpcStatus(500).getDescription().contains("500"));
  }

  @Test
  public void httpStatusToGrpcStatus_checkAgainstSpec() {
    assertEquals(Status.Code.INTERNAL, GrpcUtil.httpStatusToGrpcStatus(400).getCode());
    assertEquals(Status.Code.UNAUTHENTICATED, GrpcUtil.httpStatusToGrpcStatus(401).getCode());
    assertEquals(Status.Code.PERMISSION_DENIED, GrpcUtil.httpStatusToGrpcStatus(403).getCode());
    assertEquals(Status.Code.UNIMPLEMENTED, GrpcUtil.httpStatusToGrpcStatus(404).getCode());
    assertEquals(Status.Code.UNAVAILABLE, GrpcUtil.httpStatusToGrpcStatus(429).getCode());
    assertEquals(Status.Code.UNAVAILABLE, GrpcUtil.httpStatusToGrpcStatus(502).getCode());
    assertEquals(Status.Code.UNAVAILABLE, GrpcUtil.httpStatusToGrpcStatus(503).getCode());
    assertEquals(Status.Code.UNAVAILABLE, GrpcUtil.httpStatusToGrpcStatus(504).getCode());
    // Some other code
    assertEquals(Status.Code.UNKNOWN, GrpcUtil.httpStatusToGrpcStatus(500).getCode());

    // If transport is doing it's job, 1xx should never happen. But it may not do its job.
    assertEquals(Status.Code.INTERNAL, GrpcUtil.httpStatusToGrpcStatus(100).getCode());
    assertEquals(Status.Code.INTERNAL, GrpcUtil.httpStatusToGrpcStatus(101).getCode());
  }

  @Test
  public void httpStatusToGrpcStatus_neverOk() {
    for (int i = -1; i < 800; i++) {
      assertFalse(GrpcUtil.httpStatusToGrpcStatus(i).isOk());
    }
  }

  @Test
  public void getTransportFromPickResult_errorPickResult_waitForReady() {
    Status status = Status.UNAVAILABLE;
    PickResult pickResult = PickResult.withError(status);
    ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, true);

    assertNull(transport);
  }

  @Test
  public void getTransportFromPickResult_errorPickResult_failFast() {
    Status status = Status.UNAVAILABLE;
    PickResult pickResult = PickResult.withError(status);
    ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, false);

    assertNotNull(transport);

    ClientStream stream = transport.newStream(
        TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
        tracers);
    ClientStreamListener listener = mock(ClientStreamListener.class);
    stream.start(listener);

    verify(listener).closed(eq(status), eq(RpcProgress.PROCESSED), any(Metadata.class));
  }

  /* Status codes that a control plane should not be returned get replaced by INTERNAL. */
  @Test
  public void getTransportFromPickResult_errorPickResult_noInappropriateControlPlaneStatus() {

    // These are NOT appropriate for a control plane to return.
    ArrayList<Status> inappropriateStatus = Lists.newArrayList(
        Status.INVALID_ARGUMENT.withDescription("bad one").withCause(new RuntimeException()),
        Status.NOT_FOUND.withDescription("not here").withCause(new RuntimeException()),
        Status.ALREADY_EXISTS.withDescription("not again").withCause(new RuntimeException()),
        Status.FAILED_PRECONDITION.withDescription("naah").withCause(new RuntimeException()),
        Status.ABORTED.withDescription("nope").withCause(new RuntimeException()),
        Status.OUT_OF_RANGE.withDescription("outta range").withCause(new RuntimeException()),
        Status.DATA_LOSS.withDescription("lost").withCause(new RuntimeException()));

    for (Status status : inappropriateStatus) {
      PickResult pickResult = PickResult.withError(status);
      ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, false);

      ClientStream stream = transport.newStream(
          TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
          tracers);
      ClientStreamListener listener = mock(ClientStreamListener.class);
      stream.start(listener);

      verify(listener).closed(statusCaptor.capture(), eq(RpcProgress.PROCESSED),
          any(Metadata.class));
      Status usedStatus = statusCaptor.getValue();
      assertThat(usedStatus.getCode()).isEqualTo(Code.INTERNAL);
      assertThat(usedStatus.getDescription()).contains("Inappropriate status");
      assertThat(usedStatus.getCause()).isInstanceOf(RuntimeException.class);
    }
  }

  /* Status codes a control plane can return are not replaced. */
  @Test
  public void getTransportFromPickResult_errorPickResult_appropriateControlPlaneStatus() {

    // These ARE appropriate for a control plane to return.
    ArrayList<Status> inappropriateStatus = Lists.newArrayList(
        Status.CANCELLED,
        Status.UNKNOWN,
        Status.DEADLINE_EXCEEDED,
        Status.PERMISSION_DENIED,
        Status.RESOURCE_EXHAUSTED,
        Status.UNIMPLEMENTED,
        Status.INTERNAL,
        Status.UNAVAILABLE,
        Status.UNAUTHENTICATED);

    for (Status status : inappropriateStatus) {
      PickResult pickResult = PickResult.withError(status);
      ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, false);

      ClientStream stream = transport.newStream(
          TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
          tracers);
      ClientStreamListener listener = mock(ClientStreamListener.class);
      stream.start(listener);

      verify(listener).closed(eq(status), eq(RpcProgress.PROCESSED), any(Metadata.class));
    }
  }

  @Test
  public void getTransportFromPickResult_dropPickResult_waitForReady() {
    Status status = Status.UNAVAILABLE;
    PickResult pickResult = PickResult.withDrop(status);
    ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, true);

    assertNotNull(transport);

    ClientStream stream = transport.newStream(
        TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
        tracers);
    ClientStreamListener listener = mock(ClientStreamListener.class);
    stream.start(listener);

    verify(listener).closed(eq(status), eq(RpcProgress.DROPPED), any(Metadata.class));
  }

  @Test
  public void getTransportFromPickResult_dropPickResult_failFast() {
    Status status = Status.UNAVAILABLE;
    PickResult pickResult = PickResult.withDrop(status);
    ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, false);

    assertNotNull(transport);

    ClientStream stream = transport.newStream(
        TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
        tracers);
    ClientStreamListener listener = mock(ClientStreamListener.class);
    stream.start(listener);

    verify(listener).closed(eq(status), eq(RpcProgress.DROPPED), any(Metadata.class));
  }
}
