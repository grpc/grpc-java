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

import io.grpc.netty.EpollTcpInfoAccessor.Field;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.EnumMap;

/** Mirrors the public API of {@code io.netty.channel.epoll.EpollTcpInfo}. */
public final class FakeEpollTcpInfo {
  private final EnumMap<Field, Long> values = new EnumMap<>(Field.class);

  static EpollTcpInfoAccessor newAccessor() throws ReflectiveOperationException {
    return EpollTcpInfoAccessor.create(FakeChannel.class, FakeEpollTcpInfo.class);
  }

  public void set(Field field, long value) {
    values.put(field, value);
  }

  public void setValues(long totalRetrans, long retransmits, long rtt) {
    set(Field.TOTAL_RETRANS, totalRetrans);
    set(Field.RETRANSMITS, retransmits);
    set(Field.RTT, rtt);
  }

  long get(Field field) {
    return values.getOrDefault(field, 0L);
  }

  public int state() {
    return (int) get(Field.STATE);
  }

  public int caState() {
    return (int) get(Field.CA_STATE);
  }

  public int retransmits() {
    return (int) get(Field.RETRANSMITS);
  }

  public int probes() {
    return (int) get(Field.PROBES);
  }

  public int backoff() {
    return (int) get(Field.BACKOFF);
  }

  public int options() {
    return (int) get(Field.OPTIONS);
  }

  public int sndWscale() {
    return (int) get(Field.SND_WSCALE);
  }

  public int rcvWscale() {
    return (int) get(Field.RCV_WSCALE);
  }

  public long rto() {
    return get(Field.RTO);
  }

  public long ato() {
    return get(Field.ATO);
  }

  public long sndMss() {
    return get(Field.SND_MSS);
  }

  public long rcvMss() {
    return get(Field.RCV_MSS);
  }

  public long unacked() {
    return get(Field.UNACKED);
  }

  public long sacked() {
    return get(Field.SACKED);
  }

  public long lost() {
    return get(Field.LOST);
  }

  public long retrans() {
    return get(Field.RETRANS);
  }

  public long fackets() {
    return get(Field.FACKETS);
  }

  public long lastDataSent() {
    return get(Field.LAST_DATA_SENT);
  }

  public long lastAckSent() {
    return get(Field.LAST_ACK_SENT);
  }

  public long lastDataRecv() {
    return get(Field.LAST_DATA_RECV);
  }

  public long lastAckRecv() {
    return get(Field.LAST_ACK_RECV);
  }

  public long pmtu() {
    return get(Field.PMTU);
  }

  public long rcvSsthresh() {
    return get(Field.RCV_SSTHRESH);
  }

  public long rtt() {
    return get(Field.RTT);
  }

  public long rttvar() {
    return get(Field.RTTVAR);
  }

  public long sndSsthresh() {
    return get(Field.SND_SSTHRESH);
  }

  public long sndCwnd() {
    return get(Field.SND_CWND);
  }

  public long advmss() {
    return get(Field.ADVMSS);
  }

  public long reordering() {
    return get(Field.REORDERING);
  }

  public long totalRetrans() {
    return get(Field.TOTAL_RETRANS);
  }

  /** Mirrors {@code EpollSocketChannel.tcpInfo(EpollTcpInfo)}, copying from a source info. */
  public static final class FakeChannel extends EmbeddedChannel {
    private final FakeEpollTcpInfo source;

    public FakeChannel(FakeEpollTcpInfo source) {
      this.source = source;
    }

    public void tcpInfo(FakeEpollTcpInfo info) {
      info.values.clear();
      info.values.putAll(source.values);
    }
  }
}
