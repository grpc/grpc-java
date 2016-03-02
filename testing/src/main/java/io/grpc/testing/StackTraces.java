/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/*
 * Copyright 2011 The Bazel Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.testing;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.PrintStream;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Set;

/**
 * Utilities for stack traces.
 */
public class StackTraces {

  /**
   * Prints all stack traces to the given stream.
   *
   * @param out Stream to print to
   */
  public static void printAll(PrintStream out) {
    out.println("Starting full thread dump ...\n");
    ThreadMXBean mb = ManagementFactory.getThreadMXBean();

    // ThreadInfo has comprehensive information such as locks.
    ThreadInfo[] threadInfos = mb.dumpAllThreads(true, true);

    // But we can know whether a thread is daemon only from Thread
    Set<Thread> threads = Thread.getAllStackTraces().keySet();
    ImmutableMap<Long, Thread> threadMap = Maps.uniqueIndex(
        threads, new Function<Thread, Long>() {
          @Override public Long apply(Thread thread) {
            return thread.getId();
          }
        });

    // Dump non-daemon threads first
    for (ThreadInfo threadInfo : threadInfos) {
      Thread thread = threadMap.get(threadInfo.getThreadId());
      if (thread != null && !thread.isDaemon()) {
        dumpThreadInfo(threadInfo, thread, out);
      }
    }

    // Dump daemon threads
    for (ThreadInfo threadInfo : threadInfos) {
      Thread thread = threadMap.get(threadInfo.getThreadId());
      if (thread != null && thread.isDaemon()) {
        dumpThreadInfo(threadInfo, thread, out);
      }
    }

    long[] deadlockedThreads = mb.findDeadlockedThreads();
    if (deadlockedThreads != null) {
      out.println("Detected deadlocked threads: " + Arrays.toString(deadlockedThreads));
    }
    long[] monitorDeadlockedThreads = mb.findMonitorDeadlockedThreads();
    if (monitorDeadlockedThreads != null) {
      out.println("Detected monitor deadlocked threads: "
          + Arrays.toString(monitorDeadlockedThreads));
    }
    out.println("\nDone full thread dump.");
    out.flush();
  }

  // Adopted from ThreadInfo.toString(), without MAX_FRAMES limit
  private static void dumpThreadInfo(ThreadInfo t, Thread thread, PrintStream out) {
    out.print("\"" + t.getThreadName() + "\""
        + " Id=" + t.getThreadId() + " " + t.getThreadState());
    if (t.getLockName() != null) {
      out.print(" on " + t.getLockName());
    }
    if (t.getLockOwnerName() != null) {
      out.print(" owned by \"" + t.getLockOwnerName() + "\" Id=" + t.getLockOwnerId());
    }
    if (t.isSuspended()) {
      out.print(" (suspended)");
    }
    if (t.isInNative()) {
      out.print(" (in native)");
    }
    if (thread.isDaemon()) {
      out.print(" (daemon)");
    }
    out.print('\n');
    StackTraceElement[] stackTrace = t.getStackTrace();
    MonitorInfo[] lockedMonitors = t.getLockedMonitors();
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement ste = stackTrace[i];
      out.print("\tat " + ste.toString());
      out.print('\n');
      if (i == 0 && t.getLockInfo() != null) {
        Thread.State ts = t.getThreadState();
        switch (ts) {
          case BLOCKED:
            out.print("\t-  blocked on " + t.getLockInfo());
            out.print('\n');
            break;
          case WAITING:
            out.print("\t-  waiting on " + t.getLockInfo());
            out.print('\n');
            break;
          case TIMED_WAITING:
            out.print("\t-  waiting on " + t.getLockInfo());
            out.print('\n');
            break;
          default:
        }
      }

      for (MonitorInfo mi : lockedMonitors) {
        if (mi.getLockedStackDepth() == i) {
          out.print("\t-  locked " + mi);
          out.print('\n');
        }
      }
    }

    LockInfo[] locks = t.getLockedSynchronizers();
    if (locks.length > 0) {
      out.print("\n\tNumber of locked synchronizers = " + locks.length);
      out.print('\n');
      for (LockInfo li : locks) {
        out.print("\t- " + li);
        out.print('\n');
      }
    }
    out.print('\n');
  }
}
