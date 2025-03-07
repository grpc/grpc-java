/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import io.grpc.ServerInterceptor;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/**
 * Unlike most singleton-based filters, each StatefulFilter object has a distinct identity.
 */
class StatefulFilter implements Filter {

  static final String DEFAULT_TYPE_URL = "type.googleapis.com/grpc.test.StatefulFilter";
  private final AtomicBoolean shutdown = new AtomicBoolean();

  final int idx;
  @Nullable volatile String lastCfg = null;

  public StatefulFilter(int idx) {
    this.idx = idx;
  }

  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public void close() {
    if (!shutdown.compareAndSet(false, true)) {
      throw new ConcurrentModificationException(
          "Unexpected: StatefulFilter#close called multiple times");
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config,
      @Nullable FilterConfig overrideConfig) {
    Config cfg = (Config) config;
    // TODO(sergiitk): to be replaced when name argument passed to the constructor.
    lastCfg = cfg.getConfig();
    return null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder().append("StatefulFilter{")
        .append("idx=").append(idx);
    if (lastCfg != null) {
      sb.append(", name=").append(lastCfg);
    }
    return sb.append("}").toString();
  }

  static final class Provider implements Filter.Provider {

    private final String typeUrl;
    private final ConcurrentMap<Integer, StatefulFilter> instances = new ConcurrentHashMap<>();

    volatile int counter;

    Provider() {
      this(DEFAULT_TYPE_URL);
    }

    Provider(String typeUrl) {
      this.typeUrl = typeUrl;
    }

    @Override
    public String[] typeUrls() {
      return new String[]{ typeUrl };
    }

    @Override
    public boolean isClientFilter() {
      return true;
    }

    @Override
    public boolean isServerFilter() {
      return true;
    }

    @Override
    public synchronized StatefulFilter newInstance() {
      StatefulFilter filter = new StatefulFilter(counter++);
      instances.put(filter.idx, filter);
      return filter;
    }

    public synchronized StatefulFilter getInstance(int idx) {
      return instances.get(idx);
    }

    public synchronized ImmutableList<StatefulFilter> getAllInstances() {
      return IntStream.range(0, counter).mapToObj(this::getInstance).collect(toImmutableList());
    }

    @SuppressWarnings("UnusedMethod")
    public synchronized int getCount() {
      return counter;
    }

    @Override
    public ConfigOrError<Config> parseFilterConfig(Message rawProtoMessage) {
      return ConfigOrError.fromConfig(Config.fromProto(rawProtoMessage, typeUrl));
    }

    @Override
    public ConfigOrError<Config> parseFilterConfigOverride(Message rawProtoMessage) {
      return ConfigOrError.fromConfig(Config.fromProto(rawProtoMessage, typeUrl));
    }
  }


  static final class Config implements FilterConfig {

    private final String typeUrl;
    private final String config;

    public Config(String config, String typeUrl) {
      this.config = config;
      this.typeUrl = typeUrl;
    }

    public Config(String config) {
      this(config, DEFAULT_TYPE_URL);
    }

    public Config() {
      this("<BLANK>", DEFAULT_TYPE_URL);
    }

    public static Config fromProto(Message rawProtoMessage, String typeUrl) {
      checkNotNull(rawProtoMessage, "rawProtoMessage");
      return new Config(rawProtoMessage.toString(), typeUrl);
    }

    public String getConfig() {
      return config;
    }

    @Override
    public String typeUrl() {
      return typeUrl;
    }
  }
}
