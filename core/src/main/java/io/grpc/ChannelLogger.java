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
   * Log levels.
   */
  public enum Level {
    INFO,
    WARNING,
    ERROR
  }

  public abstract void log(Level level, String message);
}
