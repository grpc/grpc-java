package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;

import android.os.Parcel;
import com.google.common.annotations.VisibleForTesting;
import java.io.Closeable;
import javax.annotation.Nullable;

/**
 * Wraps a {@link Parcel} from the static {@link Parcel#obtain()} pool with methods that make it
 * easy to eventually {@link Parcel#recycle()} it.
 */
class ParcelHolder implements Closeable {

  @Nullable private Parcel parcel;

  /**
   * Creates a new instance that owns a {@link Parcel} newly obtained from Android's object pool.
   */
  public static ParcelHolder obtain() {
    return new ParcelHolder(Parcel.obtain());
  }

  /** Creates a new instance taking ownership of the specified {@code parcel}. */
  public ParcelHolder(Parcel parcel) {
    this.parcel = parcel;
  }

  /**
   * Returns the wrapped {@link Parcel} if we still own it.
   *
   * @throws IllegalStateException if ownership has already been given up by {@link #release()}
   */
  public Parcel get() {
    checkState(parcel != null, "get() after close()/release()");
    return parcel;
  }

  /**
   * Returns the wrapped {@link Parcel} and releases ownership of it.
   *
   * @throws IllegalStateException if ownership has already been given up by {@link #release()}
   */
  public Parcel release() {
    Parcel result = get();
    this.parcel = null;
    return result;
  }

  /**
   * Recycles the wrapped {@link Parcel} to Android's object pool, if we still own it.
   *
   * <p>Otherwise, this method has no effect.
   */
  @Override
  public void close() {
    if (parcel != null) {
      parcel.recycle();
      parcel = null;
    }
  }

  /**
   * Returns true iff this container no longer owns a {@link Parcel}.
   *
   * <p>{@link #isEmpty()} is true after all call to {@link #close()} or {@link #release()}.
   *
   * <p>Typically only used for debugging or testing since Parcel-owning code should be calling
   * {@link #close()} unconditionally.
   */
  @VisibleForTesting
  public boolean isEmpty() {
    return parcel == null;
  }
}
