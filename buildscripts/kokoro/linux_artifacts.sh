#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

. "$GRPC_JAVA_DIR"/buildscripts/kokoro/kokoro.sh
trap spongify_logs EXIT

"$GRPC_JAVA_DIR"/buildscripts/build_docker.sh
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-x86 /grpc-java/buildscripts/build_artifacts_in_docker.sh

# grpc-android, grpc-cronet and grpc-binder require the Android SDK, so build outside of Docker and
# use --include-build for its grpc-core dependency
echo y | ${ANDROID_HOME}/tools/bin/sdkmanager "build-tools;28.0.3"

# The sdkmanager needs Java 8, but now we switch to 11 as the Android builds
# require it
sudo update-java-alternatives --set java-1.11.0-openjdk-amd64
unset JAVA_HOME

LOCAL_MVN_TEMP=$(mktemp -d)
GRADLE_FLAGS="-Pandroid.useAndroidX=true"
pushd "$GRPC_JAVA_DIR/android"
../gradlew publish \
  -Dorg.gradle.parallel=false \
  -PskipCodegen=true \
  -PrepositoryDir="$LOCAL_MVN_TEMP" \
  $GRADLE_FLAGS
popd

pushd "$GRPC_JAVA_DIR/cronet"
../gradlew publish \
  -Dorg.gradle.parallel=false \
  -PskipCodegen=true \
  -PrepositoryDir="$LOCAL_MVN_TEMP" \
  $GRADLE_FLAGS
popd

pushd "$GRPC_JAVA_DIR/binder"
../gradlew publish \
  -Dorg.gradle.parallel=false \
  -PskipCodegen=true \
  -PrepositoryDir="$LOCAL_MVN_TEMP" \
  $GRADLE_FLAGS
popd

readonly MVN_ARTIFACT_DIR="${MVN_ARTIFACT_DIR:-$GRPC_JAVA_DIR/mvn-artifacts}"
mkdir -p "$MVN_ARTIFACT_DIR"
cp -r "$LOCAL_MVN_TEMP"/* "$MVN_ARTIFACT_DIR"/

"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-multiarch env \
  SKIP_TESTS=true ARCH=aarch_64 /grpc-java/buildscripts/kokoro/unix.sh
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-multiarch env \
  SKIP_TESTS=true ARCH=ppcle_64 /grpc-java/buildscripts/kokoro/unix.sh
# Use a newer GCC version. GCC 7 in multiarch has a bug:
#   internal compiler error: output_operand: invalid %-code
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-ubuntu2004 env \
  SKIP_TESTS=true ARCH=s390_64 /grpc-java/buildscripts/kokoro/unix.sh
