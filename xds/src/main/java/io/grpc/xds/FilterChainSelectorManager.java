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

package io.grpc.xds;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.GuardedBy;

/**
 * Maintains the current xDS selector and any resources using that selector. When the selector
 * changes, old resources are closed to avoid old config usages.
 */
final class FilterChainSelectorManager {
  private static final AtomicLong closerId = new AtomicLong();

  private final Object lock = new Object();
  @GuardedBy("lock")
  private FilterChainSelector selector;
  // Avoid HashSet since it does not decrease in size, forming a high water mark.
  @GuardedBy("lock")
  private TreeSet<Closer> closers = new TreeSet<Closer>(new CloserComparator());

  public FilterChainSelector register(Closer closer) {
    synchronized (lock) {
      Preconditions.checkState(closers.add(closer), "closer already registered");
      return selector;
    }
  }

  public void deregister(Closer closer) {
    synchronized (lock) {
      closers.remove(closer);
    }
  }

  /** Only safe to be called by code that is responsible for updating the selector.  */
  public FilterChainSelector getSelectorToUpdateSelector() {
    synchronized (lock) {
      return selector;
    }
  }

  public void updateSelector(FilterChainSelector newSelector) {
    TreeSet<Closer> oldClosers;
    synchronized (lock) {
      oldClosers = closers;
      closers = new TreeSet<Closer>(closers.comparator());
      selector = newSelector;
    }
    for (Closer closer : oldClosers) {
      closer.closer.run();
    }
  }

  @VisibleForTesting
  int getRegisterCount() {
    synchronized (lock) {
      return closers.size();
    }
  }

  public static final class Closer {
    private final long id = closerId.getAndIncrement();
    private final Runnable closer;

    /** {@code closer} may be run multiple times. */
    public Closer(Runnable closer) {
      this.closer = Preconditions.checkNotNull(closer, "closer");
    }
  }

  private static class CloserComparator implements Comparator<Closer> {
    @Override public int compare(Closer c1, Closer c2) {
      return Long.compare(c1.id, c2.id);
    }
  }
}
