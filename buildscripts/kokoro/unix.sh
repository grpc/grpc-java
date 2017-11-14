#!/bin/bash

# This file is used for both Linux and MacOS builds.
# TODO(zpencer): test this script for Linux

# This script assumes `set -e`. Removing it may lead to undefined behavior.
set -exu -o pipefail

export GRADLE_OPTS=-Xmx512m
export PROTOBUF_VERSION=3.4.0
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS=-I/tmp/protobuf/include
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export OS_NAME=$(uname)


# TODO(zpencer): always make sure we are using Oracle jdk8

mkdir -p /tmp/build_cache/gradle
ln -s /tmp/build_cache/gradle ~/.gradle
mkdir -p /tmp/build_cache/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)/
ln -s /tmp/build_cache/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)/ /tmp/protobuf

# Kokoro workers are stateless, so local caches will not persist
# across runs.  Always bootstrap our cache using master's cache. The
# cache should still contain cache hits for most artifacts, even if
# this is not master branch.
PLATFORM=$(uname)
ARCHIVE_FILE="depdencies_master.tgz"
CACHE_PATH="gs://grpc-temp-files/grpc-java-kokoro-build-cache/$PLATFORM/$ARCHIVE_FILE"
set +e
gsutil stat $CACHE_PATH
IS_CACHED=$?
set -e

if [[ $IS_CACHED == 0 ]]; then
  gsutil cp $CACHE_PATH .
  tar xpzf $ARCHIVE_FILE /
fi

cd ./github/grpc-java

# Proto deps
buildscripts/make_dependencies.sh # build protoc into /tmp/protobuf-${PROTOBUF_VERSION}

# Gradle build config
mkdir -p $HOME/.gradle
echo "checkstyle.ignoreFailures=false" >> $HOME/.gradle/gradle.properties
echo "failOnWarnings=true" >> $HOME/.gradle/gradle.properties
echo "errorProne=true" >> $HOME/.gradle/gradle.properties

# Run tests
./gradlew assemble generateTestProto install
pushd examples
./gradlew build
# --batch-mode reduces log spam
mvn verify --batch-mode
popd
# TODO(zpencer): also build the GAE examples


# For master branch only: If build was successful and the gradle dep
# hash is not cached, then cache it. Builds on master are serialized,
# and are in commit order for all intents and purposes.

# GITBRANCH=$(git rev-parse --abbrev-ref HEAD)
GITBRANCH='master' # TODO(zpencer): remove after testing
if [[ $GITBRANCH == 'master' && $IS_CACHED != 0 ]]; then
  tar czf $ARCHIVE_FILE /tmp/build_cache/
  gsutil cp $ARCHIVE_FILE $CACHE_PATH
fi
