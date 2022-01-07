/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.android.integrationtest;

import androidx.annotation.Nullable;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.io.InputStream;

/**
 * A helper class to create a OkHttp based channel.
 */
class TesterOkHttpChannelBuilder {
  public static ManagedChannel build(
      String host,
      int port,
      @Nullable String serverHostOverride,
      boolean useTls,
      @Nullable InputStream testCa) {
    ChannelCredentials credentials;
    if (useTls) {
      if (testCa == null) {
        credentials = TlsChannelCredentials.create();
      } else {
        try {
          credentials = TlsChannelCredentials.newBuilder().trustManager(testCa).build();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    } else {
      credentials = InsecureChannelCredentials.create();
    }

    ManagedChannelBuilder<?> channelBuilder = Grpc.newChannelBuilderForAddress(
          host, port, credentials)
        .maxInboundMessageSize(16 * 1024 * 1024);
    if (!(channelBuilder instanceof OkHttpChannelBuilder)) {
      throw new RuntimeException("Did not receive an OkHttpChannelBuilder");
    }
    if (serverHostOverride != null) {
      // Force the hostname to match the cert the server uses.
      channelBuilder.overrideAuthority(serverHostOverride);
    }
    return channelBuilder.build();
  }
}
