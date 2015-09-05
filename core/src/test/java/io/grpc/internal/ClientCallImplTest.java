package io.grpc.internal;

import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StringMarshaller;

/**
 * Tests for {@link io.grpc.internal.ClientCallImpl}.
 */
@RunWith(JUnit4.class)
public class ClientCallImplTest {

  private MethodDescriptor<String, Integer> method = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNKNOWN, "/service/method",
      new StringMarshaller(), new IntegerMarshaller());
  private ExecutorService executor = Executors.newSingleThreadExecutor();

  @Mock
  private ClientTransport mockTransport;

  @Mock
  private ClientStream mockStream;

  @Captor
  private ArgumentCaptor<ClientStreamListener> listener;

  private ClientCallImpl<String, Integer> clientCall;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    clientCall = new ClientCallImpl<String, Integer>(method, new SerializingExecutor(executor),
        CallOptions.DEFAULT, new ClientCallImpl.ClientTransportProvider() {
      @Override
      public ClientTransport get() {
        return mockTransport;
      }
    }, SharedResourceHolder.get(TIMER_SERVICE));
    when(mockTransport.newStream(any(MethodDescriptor.class), any(Metadata.class),
        listener.capture())).thenReturn(mockStream);
  }

  @Test
  public void testOnReadyBatchesFlush() {
    clientCall.start(new ClientCall.Listener<Integer>() {
      @Override
      public void onReady() {
        clientCall.sendMessage("a");
        clientCall.sendMessage("b");
        clientCall.sendMessage("c");
      }
    }, new Metadata());

    listener.getValue().onReady();

    verify(mockStream, times(3)).writeMessage(any(InputStream.class));
    verify(mockStream, times(1)).flush();
  }

  @Test
  public void testOutsideOnReadyDoesNotBatchFlush() {
    clientCall.start(new ClientCall.Listener<Integer>() {
    }, new Metadata());

    clientCall.sendMessage("a");
    clientCall.sendMessage("b");
    clientCall.sendMessage("c");

    verify(mockStream, times(3)).writeMessage(any(InputStream.class));
    verify(mockStream, times(3)).flush();
  }
}
