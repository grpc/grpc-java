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

package io.grpc;

/**
 * A Channel-specific logger provided by GRPC library to {@link LoadBalancer} implementations.
 * Information logged here goes to <string>Channelz</strong>, and to Java logger as well.
 */
public abstract class ChannelLogger {
  /**
   * Log levels.  Each level maps to its corresponding severity level when exported to Channelz, and
   * to a {@link java.util.logging.Level logger level} defined below when exported to Java logger:
   * <pre>
   * +---------------------+-------------------+
   * | ChannelLogger Level | Java Logger Level |
   * +---------------------+-------------------+
   * | INFO                | FINEST            |
   * | WARNING             | FINER             |
   * | ERROR               | FINE              |
   * +---------------------+-------------------+
   * </pre>
   */
  public enum Level {
    INFO,
    WARNING,
    ERROR
  }

  /**
   * Logs a message, which is exported on Channelz as well as the Java logger for this class.
   */
  public abstract void log(Level level, String message);
}
