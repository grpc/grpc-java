/*
 * Copyright 2026 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.epoll.EpollTcpInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EpollTcpInfoAccessorTest {

  @Test
  public void create_resolvesAllFieldsOnNettyEpollClasses() throws Exception {
    // Class literals do not trigger epoll's native initialization, so this runs on any platform.
    EpollTcpInfoAccessor accessor =
        EpollTcpInfoAccessor.create(EpollSocketChannel.class, EpollTcpInfo.class);

    assertThat(accessor.isEpollSocketChannel(new EmbeddedChannel())).isFalse();
  }

  @Test
  public void create_missingGetter_throws() {
    assertThrows(NoSuchMethodException.class,
        () -> EpollTcpInfoAccessor.create(FakeEpollTcpInfo.FakeChannel.class, Object.class));
  }
}
