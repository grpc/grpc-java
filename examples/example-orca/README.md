gRPC ORCA Example
================

The ORCA example consists of a Hello World client and a Hello World server. Out-of-the-box the 
client behaves the same the hello-world version and the server behaves similar to the
example-hostname. In addition, they have been integrated with backend metrics reporting features.

### Build the example

Build the ORCA hello-world example client & server. From the `grpc-java/examples/examples-orca`
directory:
```
$ ../gradlew installDist
```

This creates the scripts `build/install/example-orca/bin/custom-backend-metrics-client` and
`build/install/example-orca/bin/custom-backend-metrics-server`.

### Run the example

To use ORCA, you have to instrument both the client and the server.
At the client, in your own load balancer policy, you use gRPC APIs to install listeners to receive
per-query and out-of-band metric reports.
At the server, you add a server interceptor provided by gRPC in order to send per-query backend metrics.
And you register a bindable service, also provided by gRPC, in order to send out-of-band backend metrics.
Meanwhile, you update the metrics data from your own measurements.

That's it! In this example, we simply put all the necessary pieces together to demonstrate the
metrics reporting mechanism.

1. To start the ORCA enabled example server on its default port of 50051, run:
```
$ ./build/install/example-orca/bin/custom-backend-metrics-server
```

2. In a different terminal window, run the ORCA enabled example client:
```
$ ./build/install/example-orca/bin/custom-backend-metrics-client "orca tester" 1500
```
The first command line argument (`orca tester`) is the name you wish to include in
the greeting request to the server and the second argument
(`1500`) is the time period (in milliseconds) you want to run the client before it shut downed so that it will show 
more periodic backend metrics reports. You are expected to see the metrics data printed out. Try it!

