/*
 * Copyright 2026 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GoAwayDisconnectErrorTest {

  @Test
  public void standardErrorCode() {
    GoAwayDisconnectError error = new GoAwayDisconnectError(GrpcUtil.Http2Error.NO_ERROR);
    assertEquals("GOAWAY NO_ERROR", error.toErrorString());
    assertEquals(GrpcUtil.Http2Error.NO_ERROR, error.getErrorCode());
  }

  @Test
  public void standardErrorCodeFromLong() {
    GoAwayDisconnectError error = new GoAwayDisconnectError(0x0L);
    assertEquals("GOAWAY NO_ERROR", error.toErrorString());
    assertEquals(GrpcUtil.Http2Error.NO_ERROR, error.getErrorCode());

    GoAwayDisconnectError cancelError = new GoAwayDisconnectError(0x8L);
    assertEquals("GOAWAY CANCEL", cancelError.toErrorString());
    assertEquals(GrpcUtil.Http2Error.CANCEL, cancelError.getErrorCode());
  }

  @Test
  public void nullErrorCodeFallsBackToInternalError() {
    GoAwayDisconnectError error = new GoAwayDisconnectError((GrpcUtil.Http2Error) null);
    assertEquals("GOAWAY INTERNAL_ERROR", error.toErrorString());
    assertEquals(GrpcUtil.Http2Error.INTERNAL_ERROR, error.getErrorCode());
  }

  @Test
  public void unrecognizedErrorCodeFromLongFallsBackToInternalError() {
    // Apache httpd APR_TIMEUP error code
    GoAwayDisconnectError apacheError = new GoAwayDisconnectError(70007L);
    assertEquals("GOAWAY INTERNAL_ERROR", apacheError.toErrorString());
    assertEquals(GrpcUtil.Http2Error.INTERNAL_ERROR, apacheError.getErrorCode());

    // Arbitrary unknown error code
    GoAwayDisconnectError unknownError = new GoAwayDisconnectError(0x12345678L);
    assertEquals("GOAWAY INTERNAL_ERROR", unknownError.toErrorString());
    assertEquals(GrpcUtil.Http2Error.INTERNAL_ERROR, unknownError.getErrorCode());

    // Negative code
    GoAwayDisconnectError negativeError = new GoAwayDisconnectError(-1L);
    assertEquals("GOAWAY INTERNAL_ERROR", negativeError.toErrorString());
    assertEquals(GrpcUtil.Http2Error.INTERNAL_ERROR, negativeError.getErrorCode());
  }

  @Test
  public void equalsAndHashCode() {
    GoAwayDisconnectError err1 = new GoAwayDisconnectError(70007L);
    GoAwayDisconnectError err2 = new GoAwayDisconnectError(GrpcUtil.Http2Error.INTERNAL_ERROR);
    GoAwayDisconnectError err3 = new GoAwayDisconnectError((GrpcUtil.Http2Error) null);
    GoAwayDisconnectError err4 = new GoAwayDisconnectError(GrpcUtil.Http2Error.NO_ERROR);

    assertEquals(err1, err2);
    assertEquals(err2, err3);
    assertEquals(err1.hashCode(), err2.hashCode());
    assertEquals(err2.hashCode(), err3.hashCode());

    assertNotEquals(err1, err4);
    assertNotEquals(err1.hashCode(), err4.hashCode());
  }
}
