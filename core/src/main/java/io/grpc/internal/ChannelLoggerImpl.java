/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ChannelLogger;
import io.grpc.InternalChannelz.ChannelTrace.Event;
import io.grpc.InternalChannelz.ChannelTrace.Event.Severity;

final class ChannelLoggerImpl extends ChannelLogger {
  private final ChannelTracer tracer;
  private final TimeProvider time;

  ChannelLoggerImpl(ChannelTracer tracer, TimeProvider time) {
    this.tracer = checkNotNull(tracer, "tracer");
    this.time = checkNotNull(time, "time");
  }

  @Override
  public void log(Level level, String msg) {
    tracer.reportEvent(new Event.Builder()
        .setDescription(msg)
        .setSeverity(toTracerSeverity(level))
        .setTimestampNanos(time.currentTimeNanos())
        .build());
  }

  private Severity toTracerSeverity(Level level) {
    switch (level) {
      case ERROR:
        return Severity.CT_ERROR;
      case WARNING:
        return Severity.CT_WARNING;
      default:
        return Severity.CT_INFO;
    }
  }
}
