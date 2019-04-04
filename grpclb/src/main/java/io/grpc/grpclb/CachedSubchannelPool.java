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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link SubchannelPool} that keeps returned {@link Subchannel}s for a given time before it's
 * shut down by the pool.
 */
final class CachedSubchannelPool implements SubchannelPool {
  private static final Attributes.Key<AtomicReference<SubchannelStateListener>> STATE_LISTENER =
      Attributes.Key.create("io.grpc.grpclb.CachedSubchannelPool.stateListener");

  private final HashMap<EquivalentAddressGroup, CacheEntry> cache =
      new HashMap<>();

  private Helper helper;

  @VisibleForTesting
  static final long SHUTDOWN_TIMEOUT_MS = 10000;

  @Override
  public void init(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public Subchannel takeOrCreateSubchannel(
      EquivalentAddressGroup eag, Attributes defaultAttributes, SubchannelStateListener listener) {
    final CacheEntry entry = cache.remove(eag);
    final Subchannel subchannel;
    if (entry == null) {
      final Attributes attrs = defaultAttributes.toBuilder()
          .set(STATE_LISTENER, new AtomicReference<>(listener))
          .build();
      subchannel = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
          .setAddresses(eag)
          .setAttributes(attrs)
          .setStateListener(new SubchannelStateListener() {
              @Override
              public void onSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
                CacheEntry cached = cache.get(subchannel.getAddresses());
                if (cached == null || cached.subchannel != subchannel) {
                  // Given subchannel is not cached, notify the listener
                  attrs.get(STATE_LISTENER).get().onSubchannelState(subchannel, newState);
                } else {
                  cached.state = newState;
                }
              }
            })
          .build());
    } else {
      subchannel = entry.subchannel;
      entry.shutdownTimer.cancel();
      subchannel.getAttributes().get(STATE_LISTENER).set(listener);
      // Make the listener up-to-date with the latest state in case it has changed while it's in the
      // cache.
      helper.getSynchronizationContext().execute(new Runnable() {
          @Override
          public void run() {
            subchannel.getAttributes().get(STATE_LISTENER).get()
                .onSubchannelState(subchannel, entry.state);
          }
        });
    }
    return subchannel;
  }

  @Override
  public void returnSubchannel(Subchannel subchannel, ConnectivityStateInfo lastKnownState) {
    CacheEntry prev = cache.get(subchannel.getAddresses());
    if (prev != null) {
      // Returning the same Subchannel twice has no effect.
      // Returning a different Subchannel for an already cached EAG will cause the
      // latter Subchannel to be shutdown immediately.
      if (prev.subchannel != subchannel) {
        subchannel.shutdown();
      }
      return;
    }
    final ShutdownSubchannelTask shutdownTask = new ShutdownSubchannelTask(subchannel);
    ScheduledHandle shutdownTimer =
        helper.getSynchronizationContext().schedule(
            shutdownTask, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            helper.getScheduledExecutorService());
    CacheEntry entry = new CacheEntry(subchannel, shutdownTimer, lastKnownState);
    cache.put(subchannel.getAddresses(), entry);
  }

  @Override
  public void clear() {
    for (CacheEntry entry : cache.values()) {
      entry.shutdownTimer.cancel();
      entry.subchannel.shutdown();
    }
    cache.clear();
  }

  @VisibleForTesting
  final class ShutdownSubchannelTask implements Runnable {
    private final Subchannel subchannel;

    private ShutdownSubchannelTask(Subchannel subchannel) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
    }

    // This runs in channelExecutor
    @Override
    public void run() {
      CacheEntry entry = cache.remove(subchannel.getAddresses());
      checkState(entry.subchannel == subchannel, "Inconsistent state");
      subchannel.shutdown();
    }
  }

  private static class CacheEntry {
    final Subchannel subchannel;
    final ScheduledHandle shutdownTimer;
    ConnectivityStateInfo state;

    CacheEntry(Subchannel subchannel, ScheduledHandle shutdownTimer, ConnectivityStateInfo state) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.shutdownTimer = checkNotNull(shutdownTimer, "shutdownTimer");
      this.state = checkNotNull(state, "state");
    }
  }
}
