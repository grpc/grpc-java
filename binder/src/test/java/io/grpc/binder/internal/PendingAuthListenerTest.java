package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.NoopServerCall;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class PendingAuthListenerTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock ServerCallHandler<Object, Object> next;
  @Mock ServerCall.Listener<Object> delegate;

  private final ServerCall<Object, Object> call = new NoopServerCall<>();
  private final Metadata headers = new Metadata();
  private final PendingAuthListener<Object, Object> listener = new PendingAuthListener<>();

  @Before
  public void setUp() {
    when(next.startCall(call, headers)).thenReturn(delegate);
  }

  @Test
  public void onCallbacks_noOpBeforeStartCall() {
    listener.onReady();
    listener.onMessage("foo");
    listener.onEvent("bar");
    listener.onHalfClose();
    listener.onComplete();

    verifyNoInteractions(delegate);
  }

  @Test
  public void onCallbacks_runsPendingCallbacksAfterStartCall() {
    String message = "foo";
    String event = "bar";

    // Act 1
    listener.onReady();
    listener.onMessage(message);
    listener.onEvent(event);
    listener.startCall(call, headers, next);

    // Assert 1
    InOrder order = Mockito.inOrder(delegate);
    order.verify(delegate).onReady();
    order.verify(delegate).onMessage(message);
    order.verify(delegate).onEvent(event);

    // Act 2
    listener.onHalfClose();
    listener.onComplete();

    // Assert 2
    order.verify(delegate).onHalfClose();
    order.verify(delegate).onComplete();
  }

  @Test
  public void onCallbacks_withCancellation_runsPendingCallbacksAfterStartCall() {
    listener.onReady();
    listener.onCancel();
    listener.startCall(call, headers, next);

    InOrder order = Mockito.inOrder(delegate);
    order.verify(delegate).onReady();
    order.verify(delegate).onCancel();
  }

  @Test
  public void whenStartCallFails_closesTheCallWithInternalStatus() {
    IllegalStateException exception = new IllegalStateException("oops");
    when(next.startCall(any(), any())).thenThrow(exception);
    FakeServerCall fakeCall = new FakeServerCall();

    listener.onReady();
    listener.startCall(fakeCall, headers, next);

    Status status = fakeCall.closeStatus;
    assertThat(status).isNotNull();
    assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(status.getCause()).isSameInstanceAs(exception);
  }

  private static final class FakeServerCall extends NoopServerCall<Object, Object> {
    @Nullable Status closeStatus;

    @Override
    public void close(Status status, Metadata trailers) {
      checkState(closeStatus == null, "call already closed");
      closeStatus = status;
    }
  }
}
