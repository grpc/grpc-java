/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.perfmark;


/**
 * Internal {@link PerfTag.TagFactory} and {@link PerfMarkTask} accessor. This is intended for use
 * by io.grpc.perfmark, and the specifically supported packages that utilize PerfMark. If you
 * *really* think you need to use this, contact the gRPC team first.
 */
public final class InternalPerfMark {

  private InternalPerfMark() {}

  /** Expose class to allow packages that utilize PerfMark to get PerfMarkTask instances. */
  public abstract static class InternalPerfMarkTask extends PerfMarkTask {
    public InternalPerfMarkTask() {}
  }

  /** Expose methods that create PerfTag to packages that utilize PerfMark. */
  private static final long NULL_NUMERIC_TAG = 0;
  private static final String NULL_STRING_TAG = null;

  public static PerfTag createPerfTag(long numericTag, String stringTag) {
    return PerfTag.TagFactory.create(numericTag, stringTag);
  }

  public static PerfTag createPerfTag(String stringTag) {
    return PerfTag.TagFactory.create(NULL_NUMERIC_TAG, stringTag);
  }

  public static PerfTag createPerfTag(long numericTag) {
    return PerfTag.TagFactory.create(numericTag, NULL_STRING_TAG);
  }
}
