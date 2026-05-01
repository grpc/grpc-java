/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.examples.nameresolve;

import com.google.common.collect.ImmutableMap;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.grpc.examples.loadbalance.LoadBalanceClient.exampleServiceName;

public class ExampleNameResolver extends NameResolver {

    private final URI uri;
    private final Map<String, List<InetSocketAddress>> addrStore;
    private Listener2 listener;

    public ExampleNameResolver(URI targetUri) {
        this.uri = targetUri;
        // This is a fake name resolver, so we just hard code the address here.
        addrStore = ImmutableMap.<String, List<InetSocketAddress>>builder()
                .put(exampleServiceName,
                        Stream.iterate(NameResolveServer.startPort, p -> p + 1)
                                .limit(NameResolveServer.serverCount)
                                .map(port -> new InetSocketAddress("localhost", port))
                                .collect(Collectors.toList())
                )
                .build();
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
        List<InetSocketAddress> addresses = addrStore.get(uri.getPath().substring(1));
        try {
            List<EquivalentAddressGroup> equivalentAddressGroup = addresses.stream()
                    // convert to socket address
                    .map(this::toSocketAddress)
                    // every socket address is a single EquivalentAddressGroup, so they can be accessed randomly
                    .map(Arrays::asList)
                    .map(this::addrToEquivalentAddressGroup)
                    .collect(Collectors.toList());

            ResolutionResult resolutionResult = ResolutionResult.newBuilder()
                    .setAddresses(equivalentAddressGroup)
                    .build();

            this.listener.onResult(resolutionResult);

        } catch (Exception e) {
            // when error occurs, notify listener
            this.listener.onError(Status.UNAVAILABLE.withDescription("Unable to resolve host ").withCause(e));
        }
    }

    private SocketAddress toSocketAddress(InetSocketAddress address) {
        return new InetSocketAddress(address.getHostName(), address.getPort());
    }

    private EquivalentAddressGroup addrToEquivalentAddressGroup(List<SocketAddress> addrList) {
        return new EquivalentAddressGroup(addrList);
    }
}
