#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

"$GRPC_JAVA_DIR"/buildscripts/build_docker.sh
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh /grpc-java/buildscripts/build_artifacts_in_docker.sh

# grpc-android and grpc-cronet require the Android SDK, so build outside of Docker and
# use --include-build for its grpc-core dependency
echo y | ${ANDROID_HOME}/tools/bin/sdkmanager "build-tools;28.0.3"
LOCAL_MVN_TEMP=$(mktemp -d)
pushd "$GRPC_JAVA_DIR/android"
../gradlew publish \
  --include-build "$GRPC_JAVA_DIR" \
  -Dorg.gradle.parallel=false \
  -PskipCodegen=true \
  -PrepositoryDir="$LOCAL_MVN_TEMP"
popd

pushd "$GRPC_JAVA_DIR/cronet"
../gradlew publish \
  --include-build "$GRPC_JAVA_DIR" \
  -Dorg.gradle.parallel=false \
  -PskipCodegen=true \
  -PrepositoryDir="$LOCAL_MVN_TEMP"
popd

readonly MVN_ARTIFACT_DIR="${MVN_ARTIFACT_DIR:-$GRPC_JAVA_DIR/mvn-artifacts}"
mkdir -p "$MVN_ARTIFACT_DIR"
cp -r "$LOCAL_MVN_TEMP"/* "$MVN_ARTIFACT_DIR"/

# for aarch64 platform
apt-get install -y wget autoconf automake libtool g++-aarch64-linux-gnu g++ make

apt-get install software-properties-common -y
add-apt-repository ppa:openjdk-r/ppa -y
apt-get update
apt install openjdk-8-jdk -y
export JAVA_HOME=`dirname $(dirname $(update-alternatives --list javac |grep java-8))`
update-ca-certificates -f
SKIP_TESTS=true ARCH=aarch_64 ./buildscripts/kokoro/unix.sh
