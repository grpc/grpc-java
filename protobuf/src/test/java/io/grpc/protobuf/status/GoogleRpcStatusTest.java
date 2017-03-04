/*
 * Copyright 2017, Google Inc. All rights reserved.
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

package io.grpc.protobuf.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GoogleRpcStatus}. */
@RunWith(JUnit4.class)
public class GoogleRpcStatusTest {
  private Metadata metadata;

  @Before
  public void setup() {
    metadata = new Metadata();
    metadata.put(METADATA_KEY, METADATA_VALUE);
  }

  @Test
  public void statusRuntimeException() throws Exception {
    StatusRuntimeException sre = GoogleRpcStatus.toStatusRuntimeException(STATUS_PROTO);
    verifyStatusRuntimeException(sre);
  }

  @Test
  public void statusRuntimeExceptionWithMetadata() throws Exception {
    StatusRuntimeException sre = GoogleRpcStatus.toStatusRuntimeException(STATUS_PROTO, metadata);
    verifyStatusRuntimeException(sre);
    verifyMetadata(sre.getTrailers());
  }

  @Test
  public void statusRuntimeExceptionWithNullMetadata() throws Exception {
    try {
      GoogleRpcStatus.toStatusRuntimeException(STATUS_PROTO, null);
      fail("NullPointerException expected");
    } catch (NullPointerException expectedException) {
    }
  }

  @Test
  public void statusException() throws Exception {
    StatusException se = GoogleRpcStatus.toStatusException(STATUS_PROTO);
    verifyStatusException(se);
  }

  @Test
  public void statusExceptionWithMetadata() throws Exception {
    StatusException se = GoogleRpcStatus.toStatusException(STATUS_PROTO, metadata);
    verifyStatusException(se);
    verifyMetadata(se.getTrailers());
  }

  @Test
  public void statusExceptionWithNullMetadata() throws Exception {
    try {
      GoogleRpcStatus.toStatusException(STATUS_PROTO, null);
      fail("NullPointerException expected");
    } catch (NullPointerException expectedException) {
    }
  }

  @Test
  public void invalidStatusCodeToStatusRuntimeException() throws Exception {
    try {
      GoogleRpcStatus.toStatusRuntimeException(INVALID_STATUS_PROTO);
      fail("IllegalArgumentException expected");
    } catch (IllegalArgumentException expectedException) {
    }
  }

  @Test
  public void invalidStatusCodeToStatusException() throws Exception {
    try {
      GoogleRpcStatus.toStatusException(INVALID_STATUS_PROTO);
      fail("IllegalArgumentException expected");
    } catch (IllegalArgumentException expectedException) {
    }
  }

  @Test
  public void fromThrowableForNullTrailers() {
    Status status = Status.fromCodeValue(0);
    assertNull(GoogleRpcStatus.fromThrowable(status.asRuntimeException()));
    assertNull(GoogleRpcStatus.fromThrowable(status.asException()));
  }

  private void verifyStatusRuntimeException(StatusRuntimeException sre) {
    assertEquals(STATUS_PROTO.getCode(), sre.getStatus().getCode().value());
    assertEquals(STATUS_PROTO.getMessage(), sre.getStatus().getDescription());
    assertEquals(STATUS_PROTO, GoogleRpcStatus.fromThrowable(sre));
  }

  private void verifyStatusException(StatusException se) {
    assertEquals(STATUS_PROTO.getCode(), se.getStatus().getCode().value());
    assertEquals(STATUS_PROTO.getMessage(), se.getStatus().getDescription());
    assertEquals(STATUS_PROTO, GoogleRpcStatus.fromThrowable(se));
  }

  private void verifyMetadata(Metadata m) {
    assertNotNull(m);
    assertEquals(METADATA_VALUE, m.get(METADATA_KEY));
  }

  private static final Metadata.Key<String> METADATA_KEY =
      Metadata.Key.of("test-metadata", Metadata.ASCII_STRING_MARSHALLER);
  private static final String METADATA_VALUE = "test metadata value";
  private static final com.google.rpc.Status STATUS_PROTO =
      com.google.rpc.Status.newBuilder()
          .setCode(2)
          .setMessage("status message")
          .addDetails(
              com.google.protobuf.Any.pack(
                  com.google.rpc.Status.newBuilder()
                      .setCode(13)
                      .setMessage("nested message")
                      .build()))
          .build();
  private static final com.google.rpc.Status INVALID_STATUS_PROTO =
      com.google.rpc.Status.newBuilder().setCode(-1).build();
}
