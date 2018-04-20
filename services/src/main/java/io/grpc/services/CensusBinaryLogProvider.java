package io.grpc.services;

import io.grpc.CallOptions;
import io.grpc.internal.BinaryLogProvider;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracing;

public class CensusBinaryLogProvider extends BinaryLogProviderImpl {
  @Override
  protected int priority() {
    return 6;
  }

  @Override
  public CallId getServerCallId() {
    Span currentSpan = Tracing.getTracer().getCurrentSpan();
    return CallId.fromCensusSpan(currentSpan);
  }

  @Override
  public CallId getClientCallId(CallOptions options) {
    return options.getOption(BinaryLogProvider.CLIENT_CALL_ID_CALLOPTION_KEY);
  }
}
