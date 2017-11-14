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

# kokoro workers are stateless, so local gradle caches will not persist across runs
# hash all files related to gradle, and use it as the name of a google cloud storage object
DEP_HASH=$(find . -name 'build.gradle' -or -name 'settings.gradle'  | sort | xargs md5sum | md5sum | cut -d' ' -f1)
PLATFORM=$(uname)
GRADLE_CACHE_PATH="gs://grpc-java-kokoro-gradle-cache/$PLATFORM/$DEP_HASH.tgz"
set +e
gsutil stat $GRADLE_CACHE_PATH
GRADLE_IS_CACHED=$?
set -e

if [[ $GRADLE_IS_CACHED ]]; then
  gsutil cp $GRADLE_CACHE_PATH .
  tar xpz $DEP_HASH.tgz .
fi

cd ./github/grpc-java

# Proto deps
buildscripts/make_dependencies.sh
ln -s "/tmp/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)" /tmp/protobuf

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


# if build was successful and the gradle dep hash is not cached, then cache it
if [[ $GRADLE_IS_CACHED != 0 ]]; then
  tar cvz $DEP_HASH.tgz ~/.gradle/
  gsutil cp $DEP_HASH.tgz $GRADLE_CACHE_PATH
fi
