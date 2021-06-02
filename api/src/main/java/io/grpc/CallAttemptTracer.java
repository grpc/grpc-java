package io.grpc;

/**
 * An object for collecting metrics of a parent call with multiple attempts.
 */
public abstract class CallAttemptTracer {
  // Called when about to pick a subchannel and create a new stream.
  public abstract ClientStreamTracer.Factory newAttempt(int attemptId, boolean transparentRetry);
  public abstract void attemptEnded(int attemptId, Status status);

  public abstract static class Factory {
    public abstract CallAttemptTracer newCallAttemptTracer();
  }
}
