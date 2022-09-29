package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import io.grpc.binder.internal.OneWayBinderProxy.InProcessImpl;
import io.grpc.binder.internal.OneWayBinderProxy.OutOfProcessImpl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for the {@link OneWayBinderProxy} implementations. */
@RunWith(RobolectricTestRunner.class)
public class OneWayBinderProxyTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  QueuingExecutor queuingExecutor = new QueuingExecutor();

  @Mock IBinder mockBinder;

  RecordingBinder recordingBinder = new RecordingBinder();

  @Test
  public void shouldProxyInProcessTransactionsOnExecutor() throws RemoteException {
    InProcessImpl proxy = new InProcessImpl(recordingBinder, queuingExecutor);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeInt(123);
      proxy.transact(456, parcel);
      assertThat(parcel.isEmpty()).isTrue();
      assertThat(recordingBinder.txnLog).isEmpty();
      queuingExecutor.runAllQueued();
      assertThat(recordingBinder.txnLog).hasSize(1);
      assertThat(recordingBinder.txnLog.get(0).argument).isEqualTo(123);
      assertThat(recordingBinder.txnLog.get(0).code).isEqualTo(456);
      assertThat(recordingBinder.txnLog.get(0).flags).isEqualTo(IBinder.FLAG_ONEWAY);
    }
  }

  @Test
  public void shouldNotLeakParcelsInCaseOfRejectedExecution() throws RemoteException {
    InProcessImpl proxy = new InProcessImpl(recordingBinder, queuingExecutor);
    queuingExecutor.shutdown();
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeInt(123);
      assertThrows(RejectedExecutionException.class, () -> proxy.transact(123, parcel));
      assertThat(parcel.isEmpty()).isFalse(); // Parcel didn't leak because we still own it.
    }
  }

  @Test
  public void shouldProxyOutOfProcessTransactionsSynchronously() throws RemoteException {
    OutOfProcessImpl proxy = new OutOfProcessImpl(recordingBinder);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeInt(123);
      proxy.transact(456, parcel);
      assertThat(parcel.isEmpty()).isTrue();
      assertThat(recordingBinder.txnLog).hasSize(1);
      assertThat(recordingBinder.txnLog.get(0).argument).isEqualTo(123);
      assertThat(recordingBinder.txnLog.get(0).code).isEqualTo(456);
      assertThat(recordingBinder.txnLog.get(0).flags).isEqualTo(IBinder.FLAG_ONEWAY);
    }
  }

  @Test
  public void shouldIgnoreInProcessRemoteExceptions() throws RemoteException {
    when(mockBinder.transact(anyInt(), any(), any(), anyInt())).thenThrow(RemoteException.class);
    InProcessImpl proxy = new InProcessImpl(mockBinder, queuingExecutor);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      proxy.transact(123, parcel); // Doesn't throw.
      verify(mockBinder, never()).transact(anyInt(), any(), any(), anyInt());
      queuingExecutor.runAllQueued();
    }
  }

  @Test
  public void shouldExposeOutOfProcessRemoteExceptions() throws RemoteException {
    when(mockBinder.transact(anyInt(), any(), any(), anyInt())).thenThrow(RemoteException.class);
    OutOfProcessImpl proxy = new OutOfProcessImpl(mockBinder);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      assertThrows(RemoteException.class, () -> proxy.transact(123, parcel));
    }
  }

  @Test
  public void shouldIgnoreUnknownTransactionReturnValueInProcess() throws RemoteException {
    when(mockBinder.transact(anyInt(), any(), any(), anyInt())).thenReturn(false);
    InProcessImpl proxy = new InProcessImpl(mockBinder, queuingExecutor);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      proxy.transact(123, parcel); // Doesn't throw.
      verify(mockBinder, never()).transact(anyInt(), any(), any(), anyInt());
      queuingExecutor.runAllQueued();
      verify(mockBinder).transact(eq(123), any(), any(), anyInt());
    }
  }

  @Test
  public void shouldReportImpossibleUnknownTransactionReturnValueOutOfProcess()
      throws RemoteException {
    when(mockBinder.transact(anyInt(), any(), any(), anyInt())).thenReturn(false);
    OutOfProcessImpl proxy = new OutOfProcessImpl(mockBinder);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      assertThrows(RemoteException.class, () -> proxy.transact(123, parcel));
      verify(mockBinder).transact(eq(123), any(), any(), anyInt());
    }
  }

  /** An Executor that queues up Runnables for later manual execution by a unit test. */
  static class QueuingExecutor implements Executor {
    private final Queue<Runnable> runnables = new ArrayDeque<>();
    private volatile boolean isShutdown;

    @Override
    public void execute(Runnable r) {
      if (isShutdown) {
        throw new RejectedExecutionException();
      }
      runnables.add(r);
    }

    public void runAllQueued() {
      Runnable next = null;
      while ((next = runnables.poll()) != null) {
        next.run();
      }
    }

    public void shutdown() {
      isShutdown = true;
    }
  }

  /** An immutable record of a call to {@link IBinder#transact(int, Parcel, Parcel, int)}. */
  static class TransactionRecord {
    private final int code;
    private final int argument;
    private final int flags;

    private TransactionRecord(int code, int argument, int flags) {
      this.code = code;
      this.argument = argument;
      this.flags = flags;
    }
  }

  /** A {@link Binder} that simply records every transaction it receives. */
  static class RecordingBinder extends Binder {
    private final ArrayList<TransactionRecord> txnLog = new ArrayList<>();

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      txnLog.add(new TransactionRecord(code, data.readInt(), flags));
      return true;
    }
  }

  interface ThrowingRunnable {
    void run() throws Throwable;
  }

  // TODO(jdcormie): Replace with Assert.assertThrows() once we upgrade to junit 4.13.
  private static <T extends Throwable> T assertThrows(
      Class<T> expectedThrowable, ThrowingRunnable runnable) {
    try {
      runnable.run();
    } catch (Throwable actualThrown) {
      if (expectedThrowable.isInstance(actualThrown)) {
        @SuppressWarnings("unchecked")
        T retVal = (T) actualThrown;
        return retVal;
      } else {
        AssertionError assertionError = new AssertionError("Unexpected type thrown");
        assertionError.initCause(actualThrown);
        throw assertionError;
      }
    }
    throw new AssertionError("Expected " + expectedThrowable + " but nothing was thrown");
  }
}
