/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.client;

import java.util.Map;

public abstract class ControlPlaneClientTestBase {
    protected static String getNonceForResourceType(XdsClientImpl xdsClient, Bootstrapper.ServerInfo serverInfo, XdsResourceType<?> type) {
        ControlPlaneClient controlPlaneClient = xdsClient.serverCpClientMap.get(serverInfo);
        Map<XdsResourceType<?>, String> nonceMap = controlPlaneClient.getNonce();
        if (nonceMap == null) {
            return null;
        }
        return nonceMap.get(type);
    }
}
