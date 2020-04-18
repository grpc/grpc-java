gRPC Cronet Transport
========================

**EXPERIMENTAL:**  *gRPC's Cronet transport is an experimental API. Its stability
depends on upstream Cronet's implementation, which involves some experimental features.*

This code enables using the [Chromium networking stack
(Cronet)](https://chromium.googlesource.com/chromium/src/+/master/components/cronet)
as the transport layer for gRPC on Android. This lets your Android app make
RPCs using the same networking stack as used in the Chrome browser.

Some advantages of using Cronet with gRPC:

* Bundles an OpenSSL implementation, enabling TLS connections even on older
  versions of Android without additional configuration
* Robust to Android network connectivity changes
* Support for [QUIC](https://www.chromium.org/quic)

Since gRPC's 1.24 release, the `grpc-cronet` package provides access to the 
`CronetChannelBuilder` class. Cronet jars are available on Google's Maven repository. 
See the example app at https://github.com/GoogleChrome/cronet-sample/blob/master/README.md.

## Example usage:

In your app module's `build.gradle` file, include a dependency on both `grpc-cronet` and the 
Google Play Services Client Library for Cronet

```
implementation 'io.grpc:grpc-cronet:1.28.1'
implementation 'com.google.android.gms:play-services-cronet:16.0.0'
```

In cases where Cronet cannot be loaded from Google Play services, there is a less performant 
implementation of Cronet's API that can be used. Depend on `org.chromium.net:cronet-fallback` 
to use this fall-back implementation.


You will also need permission to access the device's network state in your 
`AndroidManifest.xml`:

```
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Once the above steps are completed, you can create a gRPC Cronet channel as
follows:

```
import io.grpc.cronet.CronetChannelBuilder;
import org.chromium.net.ExperimentalCronetEngine;

...

ExperimentalCronetEngine engine =
    new ExperimentalCronetEngine.Builder(context /* Android Context */).build();
ManagedChannel channel = CronetChannelBuilder.forAddress("localhost", 8080, engine).build();
```

