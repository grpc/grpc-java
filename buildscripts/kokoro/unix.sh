#!/bin/bash

# This file is used for both Linux and MacOS builds.
# TODO(zpencer): test this script for Linux

# This script assumes `set -e`. Removing it may lead to undefined behavior.
set -exu -o pipefail
cat /VERSION

cd ./github/grpc-java

# TODO(zpencer): always make sure we are using Oracle jdk8

# Proto deps
export PROTOBUF_VERSION=3.5.1
OS_NAME=$(uname)
buildscripts/make_dependencies.sh
ln -s "/tmp/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)" /tmp/protobuf

# Gradle build config
mkdir -p $HOME/.gradle
echo "checkstyle.ignoreFailures=false" >> $HOME/.gradle/gradle.properties
echo "failOnWarnings=true" >> $HOME/.gradle/gradle.properties
echo "errorProne=true" >> $HOME/.gradle/gradle.properties
export GRADLE_OPTS=-Xmx512m

# Make protobuf discoverable by :grpc-copmiler target
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS="-I/tmp/protobuf/include"

# Run tests
./gradlew assemble generateTestProto install
pushd examples
./gradlew build
# --batch-mode reduces log spam
mvn verify --batch-mode
popd
# TODO(zpencer): also build the GAE examples

# Build release artifacts and move it to the artifacts dir
# If we want to build but not store the artifacts, do not set 'define_artifacts' in cfg file.
if [[ -z "${BUILD_MVN_ARTIFACTS:-}" ]]; then
  exit 0
fi

# TODO(zpencer): if linux builds use this script, then also build for 32bit (-m32 flag)
# Today, only macos uses this script and macos targets 64bit only

# re-build protobuf using the flags listed in RELEASING.md
rm -rf "/tmp/protobuf-$PROTOBUF_VERSION/$(uname -s)-$(uname -p)"
CONFIG_FLAGS="--disable-shared" CXX_FLAGS="-m64" LDFLAGS="" LD_LIBRARY_PATH="" buildscripts/make_dependencies.sh

LOCAL_MVN_TEMP="/tmp/mvn-repository/"
# this dir should not already exist, let it fail due to 'set -e' if it does
mkdir -p $LOCAL_MVN_TEMP
echo "repositoryDir=$LOCAL_MVN_TEMP" >> gradle.properties

./gradlew clean grpc-compiler:build grpc-compiler:uploadArchives -PtargetArch=x86_64 -Dorg.gradle.parallel=false

MVN_ARTIFACT_DIR="$PWD/mvn-artifacts"
mkdir $MVN_ARTIFACT_DIR
mv $LOCAL_MVN_TEMP/* $MVN_ARTIFACT_DIR
