#!/bin/bash
set -ex

cp -r /grpc-java /workspace
cd /workspace
./gradlew build -PskipAndroid=true -PskipCodegen=true