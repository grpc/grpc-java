/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.verify;

import io.grpc.Attributes;
import io.grpc.Codec;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.internal.AbstractStream.Phase;
import io.grpc.internal.MessageFramerTest.ByteWritableBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test for {@link AbstractClientStream}.  This class tries to test functionality in
 * AbstractClientStream, but not in any super classes.
 */
@RunWith(JUnit4.class)
public class AbstractClientStreamTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Mock private ClientStreamListener mockListener;
  @Captor private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  private final WritableBufferAllocator allocator = new WritableBufferAllocator() {
    @Override
    public WritableBuffer allocate(int capacityHint) {
      return new ByteWritableBuffer(capacityHint);
    }
  };

  @Test
  public void cancel_doNotAcceptOk() {
    for (Code code : Code.values()) {
      ClientStreamListener listener = new NoopClientStreamListener();
      AbstractClientStream stream = new BaseAbstractClientStream(allocator);
      stream.start(listener);
      if (code != Code.OK) {
        stream.cancel(Status.fromCodeValue(code.value()));
      } else {
        try {
          stream.cancel(Status.fromCodeValue(code.value()));
          fail();
        } catch (IllegalArgumentException e) {
          // ignore
        }
      }
    }
  }

  @Test
  public void cancel_failsOnNull() {
    ClientStreamListener listener = new NoopClientStreamListener();
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(listener);
    thrown.expect(NullPointerException.class);

    stream.cancel(null);
  }

  @Test
  public void cancel_notifiesOnlyOnce() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator) {
      @Override
      protected void sendCancel(Status errorStatus) {
        transportReportStatus(errorStatus, true/*stop delivery*/, new Metadata());
      }
    };
    stream.start(mockListener);

    stream.cancel(Status.DEADLINE_EXCEEDED);

    verify(mockListener).closed(isA(Status.class), isA(Metadata.class));
  }

  @Test
  public void startFailsOnNullListener() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);

    thrown.expect(NullPointerException.class);

    stream.start(null);
  }

  @Test
  public void cantCallStartTwice() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    thrown.expect(IllegalStateException.class);

    stream.start(mockListener);
  }

  @Test
  public void deframeFailed_notifiesListener() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator) {
      @Override
      protected void sendCancel(Status errorStatus) {
        transportReportStatus(errorStatus, true/*stop delivery*/, new Metadata());
      }
    };
    stream.start(mockListener);

    stream.deframeFailed(new RuntimeException("something bad"));

    verify(mockListener).closed(statusCaptor.capture(), isA(Metadata.class));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void inboundDataReceived_failsOnNullFrame() {
    ClientStreamListener listener = new NoopClientStreamListener();
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(listener);
    thrown.expect(NullPointerException.class);

    stream.inboundDataReceived(null);
  }

  @Test
  public void inboundDataReceived_failsOnNoHeaders() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    stream.inboundPhase(Phase.HEADERS);

    stream.inboundDataReceived(ReadableBuffers.empty());

    verify(mockListener).closed(statusCaptor.capture(), isA(Metadata.class));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void inboundHeadersReceived_notifiesListener() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    Metadata headers = new Metadata();

    stream.inboundHeadersReceived(headers);
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void inboundHeadersReceived_failsOnPhaseStatus() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    Metadata headers = new Metadata();
    stream.inboundPhase(Phase.STATUS);

    thrown.expect(IllegalStateException.class);

    stream.inboundHeadersReceived(headers);
  }

  @Test
  public void inboundHeadersReceived_succeedsOnPhaseMessage() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    Metadata headers = new Metadata();
    stream.inboundPhase(Phase.MESSAGE);

    stream.inboundHeadersReceived(headers);

    verify(mockListener).headersRead(headers);
  }

  @Test
  public void inboundHeadersReceived_acceptsGzipEncoding() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    Metadata headers = new Metadata();
    headers.put(GrpcUtil.MESSAGE_ENCODING_KEY, new Codec.Gzip().getMessageEncoding());

    stream.inboundHeadersReceived(headers);
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void inboundHeadersReceived_acceptsIdentityEncoding() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    Metadata headers = new Metadata();
    headers.put(GrpcUtil.MESSAGE_ENCODING_KEY, Codec.Identity.NONE.getMessageEncoding());

    stream.inboundHeadersReceived(headers);
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void rstStreamClosesStream() {
    AbstractClientStream stream = new BaseAbstractClientStream(allocator);
    stream.start(mockListener);
    // The application will call request when waiting for a message, which will in turn call this
    // on the transport thread.
    stream.requestMessagesFromDeframer(1);
    // Send first byte of 2 byte message
    stream.deframe(ReadableBuffers.wrap(new byte[] {0, 0, 0, 0, 2, 1}), false);
    Status status = Status.INTERNAL;
    // Simulate getting a reset
    stream.transportReportStatus(status, false /*stop delivery*/, new Metadata());

    assertTrue(stream.isClosed());
  }

  /**
   * No-op base class for testing.
   */
  private static class BaseAbstractClientStream extends AbstractClientStream {
    protected BaseAbstractClientStream(WritableBufferAllocator allocator) {
      super(allocator, DEFAULT_MAX_MESSAGE_SIZE, StatsTraceContext.NOOP);
    }

    @Override
    public void setAuthority(String authority) {}

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }

    @Override
    public void request(int numMessages) {}

    @Override
    protected void sendFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {}

    @Override
    protected void sendCancel(Status reason) {}

    @Override
    public int id() {
      return ABSENT_ID;
    }

    @Override
    protected void returnProcessedBytes(int processedBytes) {}
  }
}
