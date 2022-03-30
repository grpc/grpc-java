/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability.logging;

import io.grpc.Internal;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * An extension of java.util.logging.LogRecord which includes gRPC observability logging specific
 * fields.
 */
@Internal
public final class LogRecordExtension extends LogRecord {

  private final GrpcLogRecord grpcLogRecord;

  public LogRecordExtension(Level recordLevel, GrpcLogRecord record) {
    super(recordLevel, null);
    this.grpcLogRecord = record;
  }

  public GrpcLogRecord getGrpcLogRecord() {
    return grpcLogRecord;
  }

  // Adding a serial version UID since base class i.e LogRecord is Serializable
  private static final long serialVersionUID = 1L;
}
