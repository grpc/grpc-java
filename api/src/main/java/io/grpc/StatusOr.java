package io.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Either a Status or a value.
 */
public class StatusOr<T> {
  private StatusOr() {}

  public static <T> StatusOr<T> fromValue(T value) {
    StatusOr<T> result = new StatusOr<T>();
    result.hasValue = true;
    result.value = checkNotNull(value, "value");
    return result;
  }

  public static <T> StatusOr<T> fromStatus(Status status) {
    StatusOr<T> result = new StatusOr<T>();
    result.status  = checkNotNull(status, "status");
    checkArgument(!status.isOk(), "cannot use OK status: %s", status);
    result.hasValue = false;
    return result;
  }

  public boolean hasValue() {
    return hasValue;
  }

  public T value() {
    return value;
  }

  public Status status() {
    return status;
  }

  private Status status;
  private T value;
  private boolean hasValue;
}
