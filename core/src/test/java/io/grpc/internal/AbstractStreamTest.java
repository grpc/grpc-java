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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.grpc.internal.AbstractStream.Phase;
import io.grpc.internal.MessageFramerTest.ByteWritableBuffer;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class AbstractStreamTest {
  @Mock private StreamListener streamListener;

  @Mock MessageFramer framer;
  @Mock MessageDeframer deframer;

  private final WritableBufferAllocator allocator = new WritableBufferAllocator() {
    @Override
    public WritableBuffer allocate(int capacityHint) {
      return new ByteWritableBuffer(capacityHint);
    }
  };

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void onStreamAllocated_shouldNotifyReady() {
    AbstractStream stream = new AbstractStreamBase(null);

    stream.onStreamAllocated();

    verify(streamListener).onReady();
  }

  @Test
  public void setMessageCompression() {
    AbstractStream as = new AbstractStreamBase(framer, deframer);
    as.setMessageCompression(true);

    verify(framer).setMessageCompression(true);
  }

  @Test
  public void validPhaseTransitions() {
    AbstractStream stream = new AbstractStreamBase(null);
    Multimap<Phase, Phase> validTransitions = ImmutableMultimap.<Phase, Phase>builder()
        .put(Phase.HEADERS, Phase.HEADERS)
        .put(Phase.HEADERS, Phase.MESSAGE)
        .put(Phase.HEADERS, Phase.STATUS)
        .put(Phase.MESSAGE, Phase.MESSAGE)
        .put(Phase.MESSAGE, Phase.STATUS)
        .put(Phase.STATUS, Phase.STATUS)
        .build();

    for (Phase startPhase : Phase.values()) {
      for (Phase endPhase : Phase.values()) {
        if (validTransitions.containsEntry(startPhase, endPhase)) {
          stream.verifyNextPhase(startPhase, endPhase);
        } else {
          try {
            stream.verifyNextPhase(startPhase, endPhase);
            fail();
          } catch (IllegalStateException expected) {
            // continue
          }
        }
      }
    }
  }

  /**
   * Base class for testing.
   */
  private class AbstractStreamBase extends AbstractStream {
    private AbstractStreamBase(WritableBufferAllocator bufferAllocator) {
      super(allocator, DEFAULT_MAX_MESSAGE_SIZE, StatsTraceContext.NOOP);
    }

    private AbstractStreamBase(MessageFramer framer, MessageDeframer deframer) {
      super(framer, deframer);
    }

    @Override
    public void request(int numMessages) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int id() {
      return ABSENT_ID;
    }

    @Override
    protected StreamListener listener() {
      return streamListener;
    }

    @Override
    protected void internalSendFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void receiveMessage(InputStream is) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void inboundDeliveryPaused() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void remoteEndClosed() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void returnProcessedBytes(int processedBytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void deframeFailed(Throwable cause) {
      throw new UnsupportedOperationException();
    }
  }
}

