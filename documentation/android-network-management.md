# Android Connection Management

gRPC uses the device's default network to establish new connections.  These
connection attempts will fail when the device is offline, but gRPC itself does
not differentiate between a device that's offline or in airplane mode and an
unavailable backend.

gRPC does not directly monitor the device's network state. When the device loses
its network connection, existing gRPC channels rely on the OS itself to
terminate the underlying TCP connections. When the connections are terminated,
this is observed by gRPC and reported as a connection failure.

While gRPC itself does not monitor the device's network state, gRPC does provide
two API methods to allow the application to relay information about network
changes (e.g., monitored via Android's
[`ConnectivityManager`](https://developer.android.com/reference/android/net/ConnectivityManager.html))
to the gRPC channel.

***Note:*** *The information and API methods discussed in this document only
apply to the gRPC OkHttp transport. The gRPC Cronet transport (using Chromium's
networking stack) handles connection management and monitors the device network
state directly, outside of gRPC library code.*

## API Methods

*These are `@ExperimentalApi` and subject to name/behavior changes in the future.*

* [`resetConnectBackoff()`](https://github.com/grpc/grpc-java/blob/7d47ac0c127ccfdea98c6ff021c368c0bb4b7f0f/core/src/main/java/io/grpc/ManagedChannel.java#L123)
  (Since 1.8.0)
  * If the channel is currently using exponential backoff to reconnect or
    perform name resolution, invoking this method will trigger an immediate
    resolution or connection attempt and reset the connect backoff interval to
    zero.
  * If the channel is not already in a backoff state (either name resolution or
    connection attempts), this method has no effect.
  * This method should be invoked when the device's network state goes from
    unavailable to available, according to the device's network manager.
* [`prepareToLoseNetwork()`](https://github.com/grpc/grpc-java/blob/7d47ac0c127ccfdea98c6ff021c368c0bb4b7f0f/core/src/main/java/io/grpc/ManagedChannel.java#L141)
  (Since 1.11.0)
  * When the device is preparing to switch the default network from cell to
    wifi, it will maintain a brief (typically ~30 second) window where both
    connections are active. Android will notify applications of this pending
    change by the
    [`onLosing()`](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback.html#onLosing(android.net.Network,%20int))
    network callback.
  * Without awareness of this pending change, new RPCs on an already connected
    gRPC channel will continue to use the default cell network connection. When
    the OS turns off cell, these RPCs will fail.
  * Invoking this method allows existing RPCs to continue, but any subsequent
    new RPCs will trigger creation of a new connection. This will be created on
    the now-default wifi network.

[PR #4105](https://github.com/grpc/grpc-java/pull/4105) (in review) applies
exponential backoff when name resolution fails.  Currently gRPC reattempts name
resolution at fixed 60 second intervals, which is undesirable when the network
may be intermittently unreliable. For example, attempting to resolve an address
immediately after the device reestablishes a connection, as observed via the
[`onAvailable()`](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback.html#onAvailable(android.net.Network))
network callback and communicated to the gRPC channel via
`resetConnectBackoff()`, fails approximately one out of ten tries on a Pixel XL
running Android 27. With the fixed 60 second retry interval, it would be a full
minute before the connection becomes usable again. Exponential backoff fixes
this problem by allowing the initial retry attempt to occur very soon after the
original failure, quickly recovering from the types of intermittent network
blips common on mobile devices.

## Example usage:

***TODO(ericgribkoff):* Provide sample code**
