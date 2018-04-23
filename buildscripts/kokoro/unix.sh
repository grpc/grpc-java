#!/bin/bash

# This file is used for both Linux and MacOS builds.
# For Linux, this script is called inside a docker container with
# the correct environment for releases.
# To run locally:
#  ./buildscripts/kokoro/unix.sh
# For 32 bit:
#  ARCH=32 ./buildscripts/kokoro/unix.sh

# This script assumes `set -e`. Removing it may lead to undefined behavior.
set -exu -o pipefail

readonly GRPC_JAVA_DIR="$(cd $(dirname $(readlink -f "$0"))/../...; pwd)"

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

# cd to the root dir of grpc-java
cd $(dirname $0)/../..

# TODO(zpencer): always make sure we are using Oracle jdk8

# Proto deps
export PROTOBUF_VERSION=3.5.1

# ARCH is 64 bit unless otherwise specified.
export ARCH="${ARCH:-64}"

buildscripts/make_dependencies.sh

# the install dir is hardcoded in make_dependencies.sh
PROTO_INSTALL_DIR="/tmp/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)-x86_$ARCH"

# If /tmp/protobuf exists then we just assume it's a symlink created by us.
# It may be that it points to the wrong arch, so we idempotently set it now.
if [[ -L /tmp/protobuf ]]; then
  rm /tmp/protobuf
fi
ln -s $PROTO_INSTALL_DIR /tmp/protobuf;

# Set properties via flags, do not pollute gradle.properties
GRADLE_FLAGS="${GRADLE_FLAGS:-}"
GRADLE_FLAGS+=" -PtargetArch=x86_$ARCH $GRADLE_FLAGS"
GRADLE_FLAGS+=" -Pcheckstyle.ignoreFailures=false"
GRADLE_FLAGS+=" -PfailOnWarnings=true"
GRADLE_FLAGS+=" -PerrorProne=true"
GRADLE_FLAGS+=" -Dorg.gradle.parallel=true"
export GRADLE_OPTS="-Xmx512m"

# Make protobuf discoverable by :grpc-compiler
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS="-I/tmp/protobuf/include"

# Ensure all *.proto changes include *.java generated code
./gradlew assemble generateTestProto install $GRADLE_FLAGS

if [[ -z "${SKIP_CLEAN_CHECK:-}" && ! -z $(git status --porcelain) ]]; then
  git status
  echo "Error Working directory is not clean. Forget to commit generated files?"
  exit 1
fi

# Run tests
./gradlew build $GRADLE_FLAGS
pushd examples
./gradlew build $GRADLE_FLAGS
# --batch-mode reduces log spam
mvn verify --batch-mode
popd
# TODO(zpencer): also build the GAE examples

LOCAL_MVN_TEMP=$(mktemp -d)
# Note that this disables parallel=true from GRADLE_FLAGS
./gradlew clean grpc-compiler:build grpc-compiler:uploadArchives $GRADLE_FLAGS \
  -Dorg.gradle.parallel=false -PrepositoryDir=$LOCAL_MVN_TEMP

readonly MVN_ARTIFACT_DIR="${MVN_ARTIFACT_DIR:-$GRPC_JAVA_DIR/mvn-artifacts}"

if [[ ! -d $MVN_ARTIFACT_DIR/x86_$ARCH ]]; then
  mkdir -p $MVN_ARTIFACT_DIR/x86_$ARCH
fi
mv $LOCAL_MVN_TEMP/* $MVN_ARTIFACT_DIR/x86_$ARCH/
rmdir $LOCAL_MVN_TEMP
