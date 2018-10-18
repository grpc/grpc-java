package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ControlPlaneScheduler;

public final class ControlPlaneSchedulerTimeProvider implements TimeProvider {
  private final ControlPlaneScheduler scheduler;

  public ControlPlaneSchedulerTimeProvider(ControlPlaneScheduler scheduler) {
    this.scheduler = checkNotNull(scheduler, "scheduler");
  }

  @Override
  public long currentTimeNanos() {
    return scheduler.currentTimeNanos();
  }
}
