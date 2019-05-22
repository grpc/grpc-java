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

import com.google.errorprone.annotations.CompileTimeConstant;
import io.grpc.perfmark.PerfTag.TagFactory;

/**
 * PerfMark is a collection of stub methods for marking key points in the RPC lifecycle.  This
 * class is {@link io.grpc.Internal} and {@link io.grpc.ExperimentalApi}.  Do not use this yet.
 */
public final class PerfMark {
  private PerfMark() {
    throw new AssertionError("nope");
  }

  /**
   * Start a Task with a Tag to identify it; a task represents some work that spans some time, and
   * you are interested in both its start time and end time.
   *
   * @param tag a Tag object associated with the task. See {@link PerfTag} for description. Don't
   *     use 0 for the {@code numericTag} of the Tag object. 0 is reserved to represent that a task
   *     does not have a numeric tag associated. In this case, you are encouraged to use {@link
   *     #taskStart(String)} or {@link PerfTag#create(String)}.
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.
   */
  public static void taskStart(PerfTag tag, @CompileTimeConstant String taskName) {}

  /**
   * Start a Task; a task represents some work that spans some time, and you are interested in both
   * its start time and end time.
   *
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.
   */
  public static void taskStart(@CompileTimeConstant String taskName) {}

  /**
   * End a Task with a Tag to identify it; a task represents some work that spans some time, and
   * you are interested in both its start time and end time.
   *
   * @param tag a Tag object associated with the task start.  This should be the tag used for the
   *     corresponding {@link #taskStart(PerfTag, String)} call.
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.  This should
   *     be the name used by the corresponding {@link #taskStart(PerfTag, String)} call.
   */
  public static void taskEnd(PerfTag tag, @CompileTimeConstant String taskName) {}

  /**
   * End a Task with a Tag to identify it; a task represents some work that spans some time, and
   * you are interested in both its start time and end time.
   *
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.  This should
   *     be the name used by the corresponding {@link #taskStart(String)} call.
   */
  public static void taskEnd(@CompileTimeConstant String taskName) {}

  /**
   * Start a Task with a Tag to identify it in a try-with-resource statement; a task represents some
   * work that spans some time, and you are interested in both its start time and end time.
   *
   * <p>Use this in a try-with-resource statement so that task will end automatically.
   *
   * @param tag a Tag object associated with the task. See {@link PerfTag} for description. Don't
   *     use 0 for the {@code numericTag} of the Tag object. 0 is reserved to represent that a task
   *     does not have a numeric tag associated. In this case, you are encouraged to use {@link
   *     #task(String)} or {@link PerfTag#create(String)}.
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.
   */
  public static PerfMarkTask task(PerfTag tag, @CompileTimeConstant String taskName) {
    return NoopTask.INSTANCE;
  }

  /**
   * Start a Task it in a try-with-resource statement; a task represents some work that spans some
   * time, and you are interested in both its start time and end time.
   *
   * <p>Use this in a try-with-resource statement so that task will end automatically.
   *
   * @param taskName The name of the task. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this task.
   */
  public static PerfMarkTask task(@CompileTimeConstant String taskName) {
    return NoopTask.INSTANCE;
  }

  /**
   * Records an Event with a Tag to identify it.
   *
   * <p>An Event is different from a Task in that you don't care how much time it spanned. You are
   * interested in only the time it happened.
   *
   * @param tag a Tag object associated with the task. See {@link PerfTag} for description. Don't
   *     use 0 for the {@code numericTag} of the Tag object. 0 is reserved to represent that a task
   *     does not have a numeric tag associated. In this case, you are encouraged to use {@link
   *     #event(String)} or {@link PerfTag#create(String)}.
   * @param eventName The name of the event. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this event.
   */
  public static void event(PerfTag tag, @CompileTimeConstant String eventName) {}

  /**
   * Records an Event.
   *
   * <p>An Event is different from a Task in that you don't care how much time it spanned. You are
   * interested in only the time it happened.
   *
   * @param eventName The name of the event. <b>This parameter must be a compile-time constant!</b>
   *     Otherwise, instrumentation result will show "(invalid name)" for this event.
   */
  public static void event(@CompileTimeConstant String eventName) {}

  /**
   * If PerfMark instrumentation is not enabled, returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(long, String)} if PerfMark agent is enabled.
   *
   */
  public static PerfTag createTag(
      @SuppressWarnings("unused") long numericTag, @SuppressWarnings("unused") String stringTag) {
    // Warning suppression is safe as this method returns by default the NULL_PERF_TAG
    return NULL_PERF_TAG;
  }

  /**
   * If PerfMark instrumentation is not enabled returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(String)} if PerfMark agent is enabled.
   */
  public static PerfTag createTag(@SuppressWarnings("unused") String stringTag) {
    // Warning suppression is safe as this method returns by default the NULL_PERF_TAG
    return NULL_PERF_TAG;
  }

  /**
   * If PerfMark instrumentation is not enabled returns a Tag with numericTag = 0L. Replacement
   * for {@link TagFactory#create(long)} if PerfMark agent is enabled.
   */
  public static PerfTag createTag(@SuppressWarnings("unused") long numericTag) {
    // Warning suppression is safe as this method returns by default the NULL_PERF_TAG
    return NULL_PERF_TAG;
  }

  private static final PerfTag NULL_PERF_TAG = TagFactory.create();

  private static final class NoopTask extends PerfMarkTask {

    private static final PerfMarkTask INSTANCE = new NoopTask();

    NoopTask() {}

    @Override
    public void close() {}
  }
}
