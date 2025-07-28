package io.grpc.binder.internal;

import android.content.Context;
import android.os.UserHandle;
import com.google.common.base.VerifyException;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/**
 * A collection of static methods that wrap hidden Android "System APIs."
 *
 * <p>grpc-java can't call Android's @SystemApi methods directly. Being a library built outside the
 * Android source tree, the compiler just can't see these "non-SDK" methods. Instead we resort to
 * reflection but use static wrappers for javadoc and to keep call sites tidy and type safe.
 *
 * <p>>Modern Android's JRE also restricts the visibility of these methods at runtime, whether
 * reflection is used or not. Callers are responsible for ensuring our host is actually a "system
 * app". See https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
 * for more.
 */
public class SystemApis {
  private static volatile Method createContextAsUserMethod;

  /** Returns a new Context object whose methods act as if they were running in the given user. */
  @Nullable
  public static Context createContextAsUser(Context context, UserHandle userHandle, int flags) {
    try {
      if (createContextAsUserMethod == null) {
        synchronized (SystemApis.class) {
          if (createContextAsUserMethod == null) {
            createContextAsUserMethod =
                Context.class.getMethod("createContextAsUser", UserHandle.class, int.class);
          }
        }
      }
      return (Context) createContextAsUserMethod.invoke(context, userHandle, flags);
    } catch (ReflectiveOperationException e) {
      throw new VerifyException(e);
    }
  }
}
