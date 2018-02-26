#!/bin/bash

set -exu -o pipefail
cat /VERSION

cd ./github/grpc-java/cronet
./cronet_deps.sh

# Warm mvn cache with retries, in case connection to maven repo is flakey
(../gradlew --include-build .. dependencies || ../gradlew --include-build .. dependencies || ../gradlew --include-build .. dependencies) && ../gradlew --include-build .. clean

../gradlew --include-build .. build
