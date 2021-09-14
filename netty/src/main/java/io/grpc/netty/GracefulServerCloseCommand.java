/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.netty;

import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;

/**
 * A command to trigger close and allow streams naturally close.
 */
class GracefulServerCloseCommand extends WriteQueue.AbstractQueuedCommand {
  private final String goAwayDebugString;
  private final long graceTime;
  private final TimeUnit graceTimeUnit;

  public GracefulServerCloseCommand(String goAwayDebugString) {
    this(goAwayDebugString, -1, null);
  }

  public GracefulServerCloseCommand(
      String goAwayDebugString, long graceTime, TimeUnit graceTimeUnit) {
    this.goAwayDebugString = Preconditions.checkNotNull(goAwayDebugString, "goAwayDebugString");
    this.graceTime = graceTime;
    this.graceTimeUnit = graceTimeUnit;
  }

  public String getGoAwayDebugString() {
    return goAwayDebugString;
  }

  /** Has no meaning if {@code getGraceTimeUnit() == null}. */
  public long getGraceTime() {
    return graceTime;
  }

  public TimeUnit getGraceTimeUnit() {
    return graceTimeUnit;
  }
}
