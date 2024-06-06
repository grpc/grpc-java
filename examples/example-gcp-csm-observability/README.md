gRPC GCP CSM Observability Example
================

The GCP CSM Observability example consists of a Hello World client and a Hello World server and shows how to configure CSM Observability
for gRPC client and gRPC server.

## Configuration

`CsmObservabilityClient` takes the following command-line arguments -
* user  - The name you wish to be greeted by.
* target - By default, the client tries to connect to target : `xds:///helloworld:50051` and gRPC would use xDS to resolve this target and connect to the server backend. This can be overridden to change the target.
* prometheusPort - Port used for exposing prometheus metrics. Default value is 9464


`CsmObservabilityServer` takes the following command-line arguments -
* port - Port on which the Hello World server is run. Defaults to 50051.
* prometheusPort - Port used for exposing prometheus metrics. Default value is 9465

## Build the example

From the gRPC-java directory i.e
```
cd grpc-java
```

Client:
```
docker build -f examples/example-gcp-csm-observability/csm-client.Dockerfile
```
Server:
```
docker build -f examples/example-gcp-csm-observability/csm-server.Dockerfile
```

To push to a registry, add a tag to the image either by adding a `-t` flag to `docker build` command above or run:

```
docker image tag ${sha from build command above} ${tag}
```

And then push the tagged image using `docker push`


