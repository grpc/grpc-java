gRPC Hedging Example
=====================

The Hedging example demonstrates that enabling hedging
can reduce tail latency. (Users should note that enabling hedging may introduce other overhead;
and in some scenarios, such as when some server resource gets exhausted for a period of time and
almost every RPC during that time has high latency or fails, hedging may make things worse.
Setting a throttle in the service config is recommended to protect the server from too many
inappropriate retry or hedging requests.)

The server and the client in the example are basically the same as those in the
[hello world](src/main/java/io/grpc/examples/helloworld) example, except that the server mimics a
long tail of latency, and the client sends 2000 requests and can turn on and off hedging.

To mimic the latency, the server randomly delays the RPC handling by 2 seconds at 10% chance, 5
seconds at 5% chance, and 10 seconds at 1% chance.

When running the client enabling the following hedging policy

  ```json
        "hedgingPolicy": {
          "maxAttempts": 3,
          "hedgingDelay": "1s"
        }
  ```
Then the latency summary in the client log is like the following

  ```text
  Total RPCs sent: 2,000. Total RPCs failed: 0
  [Hedging enabled]
  ========================
  50% latency: 0ms
  90% latency: 6ms
  95% latency: 1,003ms
  99% latency: 2,002ms
  99.9% latency: 2,011ms
  Max latency: 5,272ms
  ========================
  ```

See [the section below](#to-build-the-examples) for how to build and run the example. The
executables for the server and the client are `hedging-hello-world-server` and
`hedging-hello-world-client`.

To disable hedging, set environment variable `DISABLE_HEDGING_IN_HEDGING_EXAMPLE=true` before
running the client. That produces a latency summary in the client log like the following

  ```text
  Total RPCs sent: 2,000. Total RPCs failed: 0
  [Hedging disabled]
  ========================
  50% latency: 0ms
  90% latency: 2,002ms
  95% latency: 5,002ms
  99% latency: 10,004ms
  99.9% latency: 10,007ms
  Max latency: 10,007ms
  ========================
  ```
