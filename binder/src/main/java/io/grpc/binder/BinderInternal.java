package io.grpc.binder;

import android.os.IBinder;

/**
 * Helper class to expose IBinderReceiver methods for legacy internal builders.
 */
public class BinderInternal {

  /**
   * Set the receiver's {@link IBinder} using {@link IBinderReceiver#set(IBinder)}.
   */
  static void setIBinder(IBinderReceiver receiver, IBinder binder) {
    receiver.set(binder);
  }
}
