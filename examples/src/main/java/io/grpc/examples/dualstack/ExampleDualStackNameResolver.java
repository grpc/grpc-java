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

package io.grpc.examples.dualstack;

import static io.grpc.examples.loadbalance.LoadBalanceClient.exampleServiceName;

import com.google.common.collect.ImmutableMap;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.examples.loadbalance.LoadBalanceServer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ExampleDualStackNameResolver extends NameResolver {

    // This is a fake name resolver, so we just hard code the address here.
    private static final ImmutableMap<String, List<List<SocketAddress>>> addrStore =
        ImmutableMap.<String, List<List<SocketAddress>>>builder()
        .put(exampleServiceName,
            Arrays.stream(LoadBalanceServer.SERVER_PORTS)
                .mapToObj(port -> getLocalAddrs(port))
                .collect(Collectors.toList())
        )
        .build();

    private Listener2 listener;

    private final URI uri;

    public ExampleDualStackNameResolver(URI targetUri) {
        this.uri = targetUri;
    }

    private static List<SocketAddress> getLocalAddrs(int port) {
        return Arrays.asList(
            new InetSocketAddress("127.0.0.1", port),
            new InetSocketAddress("::1", port));
    }

    @Override
    public String getServiceAuthority() {
        // Be consistent with behavior in grpc-go, authority is saved in Host field of URI.
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        return "no host";
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void start(Listener2 listener) {
        this.listener = listener;
        this.resolve();
    }

    @Override
    public void refresh() {
        this.resolve();
    }

    private void resolve() {
        List<List<SocketAddress>> addresses = addrStore.get(uri.getPath().substring(1));
        try {
            List<EquivalentAddressGroup> eagList = new ArrayList<>();
            for (List<SocketAddress> endpoint : addresses) {
                // every server is an EquivalentAddressGroup, so they can be accessed randomly
                eagList.add(new EquivalentAddressGroup(endpoint));
            }

            this.listener.onResult(ResolutionResult.newBuilder().setAddresses(eagList).build());
        } catch (Exception e){
            // when error occurs, notify listener
            this.listener.onError(Status.UNAVAILABLE.withDescription("Unable to resolve host ").withCause(e));
        }
    }

}
