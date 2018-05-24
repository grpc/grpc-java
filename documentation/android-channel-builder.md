# AndroidChannelBuilder

Since gRPC's 1.12 release, the `grpc-android` package provides access to the
`AndroidChannelBuilder` class. Given an Android Context, this builder will
register a network event listener upon channel construction.  The listener is
used to automatically respond to changes in the device's network state, avoiding
delays and interrupted RPCs that may otherwise occur.

Without access to an Android Context to register a network listener, gRPC uses
exponential backoff to attempt to recover from connection failures. Depending on
the currently scheduled backoff delay when the device regains connectivity,
there could be one minute or longer delay before the gRPC connection is
re-established. This problem is solved by provided an Android Context to the
AndroidChannelBuilder.  Notifications from the operating system about network
connectivity are relayed to the underlying channel to immediately reconnect upon
network recovery.

On Android API levels 24+, AndroidChannelBuilder's network listener mechanism
also allows gracefully switching from cellular to wifi connections. When a wifi
connection is established, there is a brief (typically 30 second) interval when
both cellular and wifi connections are available on the device, then the
cellular connections are shut down.  By listening for changes in the device's
default network from cellular to wifi, AndroidChannelBuilder will establish a
new connection on the wifi network for new RPCs rather.  Without
AndroidChannelBuilder's network listener, new RPCs will use an already
established cellular connection and will fail when the cellular network is
shutdown by the device.

***Note:*** *Currently, AndroidChannelBuilder is only compatible with gRPC
OkHttp. We plan to support the gRPC Cronet transport in the future, but the
network listener mechanism is only necessary with OkHttp; the Cronet library
internally handles connection management on Android devices.*

## Example usage:

In your `build.gradle` file, include a dependency on both `grpc-android` and
`grpc-okhttp`:

```
compile 'io.grpc:grpc-android:1.12.0' // CURRENT_GRPC_VERSION
compile 'io.grpc:grpc-okhttp:1.12.0' // CURRENT_GRPC_VERSION
```

You will also need permission to access the device's network state in your
`AndroidManifest.xml`:

```
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

When constructing your channel, instead of using `OkHttpChannelBuilder` or
`ManagedChannelBuilder`, use `AndroidChannelBuilder` and provide it with your
app's Context:

```
import io.grpc.android.AndroidChannelBuilder;
...
ManagedChannel channel = AndroidChannelBuilder.forAddress("localhost", 8080)
    .context(getApplicationContext())
    .build();
```

You continue to use the constructed channel exactly as you would any other
channel; gRPC will now monitor and respond to the device's network state
automatically. When you shutdown the managed channel, the network listener
registered by `AndroidChannelBuilder` will be unregistered.

