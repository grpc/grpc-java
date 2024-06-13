gRPC GCP CSM Observability Example
================

The GCP CSM Observability example consists of a Hello World client and a Hello World server and shows how to configure CSM Observability
for gRPC client and gRPC server.

## Configuration

`CsmObservabilityClient` takes the following command-line arguments -
* user - Name to be greeted.
* target - Server address. Default value is `xds:///helloworld:50051`.
  * When client tries to connect to target, gRPC would use xDS to resolve this target and connect to the server backend.
* prometheusPort - Port used for exposing prometheus metrics. Default value is `9464`.


`CsmObservabilityServer` takes the following command-line arguments -
* port - Port used for running Hello World server. Default value is `50051`.
* prometheusPort - Port used for exposing prometheus metrics. Default value is `9464`.

## Build the example

From the `grpc-java/examples/`directory i.e,
```
cd grpc-java/examples
```
Run the following to generate client and server images respectively.

Client:
```
docker build -f example-gcp-csm-observability/csm-client.Dockerfile .
```
Server:
```
docker build -f example-gcp-csm-observability/csm-server.Dockerfile .
```

To push to a registry, add a tag to the image either by adding a `-t` flag to `docker build` command above or run:

```
docker image tag ${sha from build command above} ${tag}
```

And then push the tagged image using `docker push`.
