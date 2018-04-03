Mobile gRPC Benchmarks
======================
These tools are what we use to benchmark mobile clients. Here you can find how to replicate our results and explore our results in more detail.

Replicating Results
-------------------
In order to run the benchmarks on your own device, you'll first need to clone the grpc-java repo.
```
$ git clone https://github.com/grpc/grpc-java.git
$ cd grpc-java/
```

Then install gRPC Java (not necessary for released versions).
```
$ ./gradlew install -PskipCodegen=true
```

Change directories into the `benchmarks-mobile` folder, and if you're using Android Studio, simply open the protolite_app project in Android Studio and sync and build.

Otherwise, change directories to the protolite_app folder, and run
```
$ ./gradlew installDebug
```
to build the application. From there use [`adb`](https://developer.android.com/studio/command-line/adb.html) to run the application on your device.

### Benchmarking Protobuf
First choose the protofile you want to run benchmarks on. You can examine them in more depth [here](/benchmarks-mobile/protolite_app/app/src/main/proto). Also choose whether or not to gzip the JSON during benchmarks (gzip is disabled for "Small request", since it actually adds in size). Then tap the "Run All Benchmarks" button to begin the benchmarks. Each benchmark takes about 15 seconds, 5 for warmup and calculation, and 10 for the actual benchmark. 

Note: If you tap "Run All Benchmarks", the same protofile/JSON object will be used across all benchmarks. If you run each benchmark individually, a new random protofile/JSON object will be used each time.

### Benchmarking gRPC
First, build the benchmark server. From the grpc-java directory type
```
$ ./gradlew :grpc-benchmarks:installDist
```

Ensure your Android device can access your computer over the network. This can be done either with USB tethering or a local network (USB is recommended). Then start the `qps_server` by running
```
$ ./benchmarks/build/install/grpc-benchmarks/bin/qps_server --address=localhost:50051
```
The benchmarking app expects for `qps_server` to be running on port 50051. 

Once server is up, input your IP, the number of concurrent connections you want (recommended 1, this only affects the gRPC benchmarks), the size of your payload (defaults to 100 bytes), and press the play button for the gRPC benchmarks. The benchmarks will take about 70 seconds, 10 for warmup and 60 for the benchmarks.

#### Benchmarking HTTP JSON
Make sure you're in the `http_server` directory and simply run 
```
$ ./gradlew run
```
to start the server. From there, everything is the same as the gRPC benchmarks. Benchmarks will also take about 70 seconds, 10 for warmup and 60 for the benchmarks. The app expects the server to be running on port 50052, which is already enabled by default. Make sure nothing is blocking that port before starting the server.

### Using Android Device Monitor
Since there is no reliable method of getting network packet information client side, we measured things such as total sent bytes by using [Android Device Monitor](https://developer.android.com/studio/profile/monitor.html). To do this, open the device monitor and make sure that your device is selected. Select the "Network Statistics" tab and make sure you start this before starting a benchmark. This way you can monitor the total number of packets and bytes that your device sends and receives. 

Observed Results
----------------
Below are the results from our own benchmarks. All benchmarks were run on a Nexus 7 tablet running Android 4.4.4.

### Protobuf vs. JSON
The below show how quickly protobuf can serialize/deserialize a messasge of a specific size. The last two compare JSON and gzipped JSON's performance to protobuf's.
![Speeds of protobuf serialization/deserialiation](/benchmarks-mobile/benchmark_results/protobuf_speeds.png)
![Comparison of serialization/deserialization speeds of protobuf and JSON](/benchmarks-mobile/benchmark_results/proto_vs_json.png)
![Comparison of serialization/deserialization speeds of protobuf and gzipped JSON](/benchmarks-mobile/benchmark_results/proto_speeds_gzip.png)

### gRPC vs. HTTP JSON
Below shows the latencies for various different servers and/or HTTP methods.
![Graph of latencies for RPC calls](/benchmarks-mobile/benchmark_results/latencies.png)

These graphs show how latency is affected by size of payload.
![Latencies vs Spark using various payload sizes](/benchmarks-mobile/benchmark_results/latencies_all.png)

Considerations
--------------
### Protobuf
Protobuf needs to calculate the size of its message when serializing in order to allocate a large enough byte array. However, when it's called once it gets cached, thus leading to skewed results with successive runs. We suspect this could up to double the reported speed. However, the speed at which protobuf serializes is well over 2x than JSON.

Gzip is disabled for the "Small request" proto, since it actually increases size.

### gRPC and HTTP
As you can see, the results for a POST vs. a GET are drastically different. This is due to the fact that for each POST request done in Android, an output stream needs to be opened, written to, then closed before sending the request. Using Square's OkHttp library makes this a bit better, but still results in a large difference between a gRPC request and a POST request.

Moreover, `HttpUrlConnection` does not seem to have a way to reuse the same object for multiple requests. Oracle mentions caching [here](https://docs.oracle.com/javase/6/docs/technotes/guides/net/http-keepalive.html), but it is unclear whether or not this is implemented in Android's SDK.

There was a tiny difference between using `HttpURLConnection` and the OkHttp library, but it is almost negligible. OkHttp seems to be a bit faster for smaller messages, but worse for larger. You can test this yourself by checking the 'Use OkHttp' box. 

A USB cable was used for our results in order to eliminate any discrepancies from using a wireless network. 

Contributing
------------
If you have any thoughts on what we did, or wish to contribute your own benchmark results, please let us know! We're still looking for battery benchmarks, as there is no standard way to measure this.