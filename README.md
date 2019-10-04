Java client for proxyless service mesh with gRPC demo
========================================

```bash
# May need to install git, java 8, etc. first. (sudo apt-get install openjdk-8-jdk)

$ git clone https://github.com/grpc/grpc-java.git
$ cd grpc-java
$ git checkout xds-demo
$ ./gradlew publishMavenPublicationToMavenLocal -PskipCodegen=true
$ cd examples
$ ./gradlew installDist
$ gsutil cp gs://grpclb-td-startup-script-bucket/bootstrap.json ./
$ export GRPC_XDS_BOOTSTRAP="`pwd`/bootstrap.json"
$ ./build/install/examples/bin/hello-world-client "xds-experimental:///cloud-internal-istio:cloud_mp_801133911813_805183435352036153"
```
