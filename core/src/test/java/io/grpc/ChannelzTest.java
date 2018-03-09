/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import io.grpc.internal.Channelz.ChannelStats;
import io.grpc.internal.LogId;
import io.grpc.internal.WithLogId;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChannelzTest {
  private final WithLogId subchannel = new WithLogId() {
    @Override
    public LogId getLogId() {
      return LogId.allocate("subchannel");
    }
  };

  private final WithLogId socket = new WithLogId() {
    @Override
    public LogId getLogId() {
      return LogId.allocate("socket");
    }
  };

  @Test(expected = IllegalStateException.class)
  public void channelStatsThrowsWhenInvalid() {
    new ChannelStats(
        "target",
        ConnectivityState.READY,
        /*callsStarted=*/ 1,
        /*callsSucceeded=*/ 2,
        /*callsFailed=*/ 3,
        /*lastCallStartedMillis=*/ 4,
        /*subchannels*/ ImmutableList.of(subchannel),
        /*sockets*/ ImmutableList.of(socket));
  }

  @Test
  public void channelStatsBuilder() {
    ChannelStats stats = new ChannelStats(
        "target",
        ConnectivityState.READY,
        /*callsStarted=*/ 1,
        /*callsSucceeded=*/ 2,
        /*callsFailed=*/ 3,
        /*lastCallStartedMillis=*/ 4,
        /*subchannels*/ ImmutableList.of(subchannel),
        /*sockets*/ Collections.<WithLogId>emptyList());
    ChannelStats copy = stats.toBuilder().build();
    assertEquals(stats.target, copy.target);
    assertEquals(stats.state, copy.state);
    assertEquals(stats.callsStarted, copy.callsStarted);
    assertEquals(stats.callsSucceeded, copy.callsSucceeded);
    assertEquals(stats.callsFailed, copy.callsFailed);
    assertEquals(stats.lastCallStartedMillis, copy.lastCallStartedMillis);
    assertEquals(stats.subchannels, copy.subchannels);
    assertEquals(stats.sockets, copy.sockets);
  }
}
