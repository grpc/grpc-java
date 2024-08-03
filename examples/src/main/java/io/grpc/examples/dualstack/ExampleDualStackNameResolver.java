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

import com.google.common.collect.ImmutableMap;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;

import io.grpc.examples.loadbalance.LoadBalanceServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.grpc.examples.loadbalance.LoadBalanceClient.exampleServiceName;

public class ExampleDualStackNameResolver extends NameResolver {

    private Listener2 listener;

    private final URI uri;

    private final Map<String,List<List<InetSocketAddress>>> addrStore;

    public ExampleDualStackNameResolver(URI targetUri) {
        this.uri = targetUri;
        // This is a fake name resolver, so we just hard code the address here.
        addrStore = ImmutableMap.<String,List<List<InetSocketAddress>>>builder()
            .put(exampleServiceName,
                Stream.iterate(LoadBalanceServer.startPort, p->p+1)
                    .limit(LoadBalanceServer.serverCount)
                    .map(port-> getLocalAddrs(port))
                    .collect(Collectors.toList())
            )
            .build();
    }

    private static List<InetSocketAddress> getLocalAddrs(Integer port) {
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
        List<List<InetSocketAddress>> addresses = addrStore.get(uri.getPath().substring(1));
        try {
            List<EquivalentAddressGroup> eagList = new ArrayList<>();
            for (List<InetSocketAddress> endpoint : addresses) {
                // convert to socket address
                List<SocketAddress> socketAddresses = endpoint.stream()
                    .map(this::toSocketAddress)
                    .collect(Collectors.toList());
                // every server is an EquivalentAddressGroup, so they can be accessed randomly
                eagList.add(addrToEquivalentAddressGroup(socketAddresses));
            }
            ResolutionResult resolutionResult = ResolutionResult.newBuilder()
                .setAddresses(eagList)
                .build();

            this.listener.onResult(resolutionResult);
        } catch (Exception e){
            // when error occurs, notify listener
            this.listener.onError(Status.UNAVAILABLE.withDescription("Unable to resolve host ").withCause(e));
        }
    }

    private SocketAddress toSocketAddress(InetSocketAddress address) {
        return new InetSocketAddress(address.getHostName(), address.getPort());
    }

    static List<SocketAddress> getV6Addresses(int port) throws UnknownHostException {
        List<SocketAddress> v6addresses = new ArrayList<>();
        InetAddress[] addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
        for (InetAddress address : addresses) {
            if (address.getAddress().length != 4) {
                v6addresses.add(new java.net.InetSocketAddress(address, port));
            }
        }
        return v6addresses;
    }

    static SocketAddress getV4Address(int port) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
        for (InetAddress address : addresses) {
            if (address.getAddress().length == 4) {
                return new java.net.InetSocketAddress(address, port);
            }
        }
        return null; // means it is v6 only
    }

    private EquivalentAddressGroup addrToEquivalentAddressGroup(List<SocketAddress> addrList) {
        return new EquivalentAddressGroup(addrList);
    }
}
