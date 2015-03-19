#!/bin/bash
#
# Build protoc & netty
set -ev

# Make protoc
pushd .
cd /tmp
git clone https://github.com/google/protobuf.git
cd protobuf
git checkout v3.0.0-alpha-2
./autogen.sh
# install here so we don't need sudo
./configure --prefix=/tmp/grpc-deps
make
make check
make install
cd java
mvn install
cd ../javanano
mvn install
popd

# Make and install netty
git submodule update --init
pushd .
cd lib/netty
mvn install -pl codec-http2 -am -DskipTests=true
popd