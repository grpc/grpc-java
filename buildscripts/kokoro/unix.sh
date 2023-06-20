#!/bin/bash

# This file is used for both Linux and MacOS builds.
# For Linux, this script is called inside a docker container with
# the correct environment for releases.
# To run locally:
#  ./buildscripts/kokoro/unix.sh
# For x86 32 arch:
#  ARCH=x86_32 ./buildscripts/kokoro/unix.sh
# For aarch64 arch:
#  ARCH=aarch_64 ./buildscripts/kokoro/unix.sh
# For ppc64le arch:
#  ARCH=ppcle_64 ./buildscripts/kokoro/unix.sh
# For s390x arch:
#  ARCH=s390_64 ./buildscripts/kokoro/unix.sh

# This script assumes `set -e`. Removing it may lead to undefined behavior.
set -exu -o pipefail

# It would be nicer to use 'readlink -f' here but osx does not support it.
readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

# cd to the root dir of grpc-java
cd $(dirname $0)/../..

# TODO(zpencer): always make sure we are using Oracle jdk8
if [[ -f /usr/libexec/java_home ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v"1.8.0")
fi

# ARCH is x86_64 unless otherwise specified.
ARCH="${ARCH:-x86_64}"

cat <<EOF >> gradle.properties
# defaults to -Xmx512m -XX:MaxMetaspaceSize=256m
# https://docs.gradle.org/current/userguide/build_environment.html#sec:configuring_jvm_memory
# Increased due to java.lang.OutOfMemoryError: Metaspace failures, "JVM heap
# space is exhausted", and to increase build speed
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m
EOF

ARCH="$ARCH" buildscripts/make_dependencies.sh

# Set properties via flags, do not pollute gradle.properties
GRADLE_FLAGS="${GRADLE_FLAGS:-}"
GRADLE_FLAGS+=" -PtargetArch=$ARCH"
GRADLE_FLAGS+=" -Pcheckstyle.ignoreFailures=false"
GRADLE_FLAGS+=" -PfailOnWarnings=true"
GRADLE_FLAGS+=" -PerrorProne=true"
GRADLE_FLAGS+=" -Dorg.gradle.parallel=true"
if [[ -z "${ALL_ARTIFACTS:-}" ]]; then
  GRADLE_FLAGS+=" -PskipAndroid=true"
else
  GRADLE_FLAGS+=" -Pandroid.useAndroidX=true"
fi
export GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1g'"

# Make protobuf discoverable by :grpc-compiler
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS="-I/tmp/protobuf/include"

./gradlew grpc-compiler:clean $GRADLE_FLAGS

if [[ -z "${SKIP_TESTS:-}" ]]; then
  # Ensure all *.proto changes include *.java generated code
  ./gradlew assemble generateTestProto publishToMavenLocal $GRADLE_FLAGS

  if [[ -z "${SKIP_CLEAN_CHECK:-}" && ! -z $(git status --porcelain) ]]; then
    git status
    echo "Error Working directory is not clean. Forget to commit generated files?"
    exit 1
  fi
  # Run tests
  ./gradlew build :grpc-all:jacocoTestReport $GRADLE_FLAGS
  pushd examples
  ./gradlew build $GRADLE_FLAGS
  # --batch-mode reduces log spam
  mvn verify --batch-mode
  popd
  for f in examples/example-*
  do
     pushd "$f"
     ../gradlew build $GRADLE_FLAGS
     if [ -f "pom.xml" ]; then
       # --batch-mode reduces log spam
       mvn verify --batch-mode
     fi
     popd
  done
  # TODO(zpencer): also build the GAE examples
fi

LOCAL_MVN_TEMP=$(mktemp -d)
# Note that this disables parallel=true from GRADLE_FLAGS
if [[ -z "${ALL_ARTIFACTS:-}" ]]; then
  if [[ "$ARCH" = "aarch_64" || "$ARCH" = "ppcle_64" || "$ARCH" = "s390_64" ]]; then
    GRADLE_FLAGS+=" -x grpc-compiler:generateTestProto -x grpc-compiler:generateTestLiteProto"
    GRADLE_FLAGS+=" -x grpc-compiler:testGolden -x grpc-compiler:testLiteGolden"
    GRADLE_FLAGS+=" -x grpc-compiler:testDeprecatedGolden -x grpc-compiler:testDeprecatedLiteGolden"
  fi
  ./gradlew grpc-compiler:build grpc-compiler:publish $GRADLE_FLAGS \
    -Dorg.gradle.parallel=false -PrepositoryDir=$LOCAL_MVN_TEMP
else
  ./gradlew publish :grpc-core:versionFile $GRADLE_FLAGS \
    -Dorg.gradle.parallel=false -PrepositoryDir=$LOCAL_MVN_TEMP
  pushd examples/example-hostname
  ../gradlew jibBuildTar $GRADLE_FLAGS
  popd

  readonly OTHER_ARTIFACT_DIR="${OTHER_ARTIFACT_DIR:-$GRPC_JAVA_DIR/artifacts}"
  mkdir -p "$OTHER_ARTIFACT_DIR"
  cp core/build/version "$OTHER_ARTIFACT_DIR"/
  cp examples/example-hostname/build/example-hostname.* "$OTHER_ARTIFACT_DIR"/
fi

readonly MVN_ARTIFACT_DIR="${MVN_ARTIFACT_DIR:-$GRPC_JAVA_DIR/mvn-artifacts}"

mkdir -p "$MVN_ARTIFACT_DIR"
cp -r "$LOCAL_MVN_TEMP"/* "$MVN_ARTIFACT_DIR"/
