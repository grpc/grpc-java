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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.alts.GoogleDefaultChannelBuilder;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import java.util.List;

public class XdsClientUtil {

  /**
   * Factory for creating channels to xDS severs.
   */
  abstract static class XdsChannelFactory {
    private static XdsChannelFactory DEFAULT_INSTANCE = new XdsChannelFactory() {

      /**
       * Create a channel to the first server in the given list.
       */
      @Override
      ManagedChannel create(List<ServerInfo> servers) {
        checkArgument(!servers.isEmpty(), "No management server provided.");
        ServerInfo serverInfo = servers.get(0);
        String serverUri = serverInfo.getServerUri();
        List<ChannelCreds> channelCredsList = serverInfo.getChannelCredentials();
        ManagedChannel ch = null;
        // Use the first supported channel credentials configuration.
        // Currently, only "google_default" is supported.
        for (ChannelCreds creds : channelCredsList) {
          if (creds.getType().equals("google_default")) {
            ch = GoogleDefaultChannelBuilder.forTarget(serverUri).build();
            break;
          }
        }
        if (ch == null) {
          ch = ManagedChannelBuilder.forTarget(serverUri).build();
        }
        return ch;
      }
    };

    static XdsChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to one of the provided management servers.
     */
    abstract ManagedChannel create(List<ServerInfo> servers);
  }
}
