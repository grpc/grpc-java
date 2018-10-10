/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.okhttp.DelegatingFrameWriter.getLogLevel;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.okhttp.DelegatingFrameWriter.TransportExceptionHandler;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DelegatingFrameWriterTest {

  private FrameWriter mockedFrameWriter;
  private Socket socket;
  private TransportExceptionHandler transportExceptionHandler;
  private DelegatingFrameWriter delegatingFrameWriter;

  @Before
  public void setUp() {
    mockedFrameWriter = mock(FrameWriter.class);
    socket = mock(Socket.class);
    transportExceptionHandler = mock(TransportExceptionHandler.class);
    delegatingFrameWriter =
        new DelegatingFrameWriter(mockedFrameWriter, socket, transportExceptionHandler);
  }

  @Test
  public void exception() throws IOException {
    IOException exception = new IOException("some exception");
    doThrow(exception).when(mockedFrameWriter)
        .synReply(false, 100, new ArrayList<Header>());

    delegatingFrameWriter.synReply(false, 100, new ArrayList<Header>());

    verify(transportExceptionHandler).onException(exception);
    verify(mockedFrameWriter).synReply(false, 100, new ArrayList<Header>());
  }

  @Test
  public void unknownException() {
    assertThat(getLogLevel(new Exception())).isEqualTo(Level.INFO);
  }

  @Test
  public void quiet() {
    assertThat(getLogLevel(new IOException("Socket closed"))).isEqualTo(Level.FINE);
  }

  @Test
  public void nonquiet() {
    assertThat(getLogLevel(new IOException("foo"))).isEqualTo(Level.INFO);
  }

  @Test
  public void nullMessage() {
    IOException e = new IOException();
    assertThat(e.getMessage()).isNull();
    assertThat(getLogLevel(e)).isEqualTo(Level.INFO);
  }
}