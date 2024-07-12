#!/bin/bash

set -exu -o pipefail

BASE_DIR="$(pwd)"

# Install gRPC and codegen for the Android examples
# (a composite gradle build can't find protoc-gen-grpc-java)

cd "$BASE_DIR/github/grpc-java"

export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS=-I/tmp/protobuf/include
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export OS_NAME=$(uname)

cat <<EOF >> gradle.properties
# defaults to -Xmx512m -XX:MaxMetaspaceSize=256m
# https://docs.gradle.org/current/userguide/build_environment.html#sec:configuring_jvm_memory
# Increased due to java.lang.OutOfMemoryError: Metaspace failures, "JVM heap
# space is exhausted", and to increase build speed
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m
EOF

export ANDROID_HOME=/tmp/Android/Sdk
mkdir -p "${ANDROID_HOME}/cmdline-tools"
curl -Ls -o cmdline.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
unzip -qd "${ANDROID_HOME}/cmdline-tools" cmdline.zip
rm cmdline.zip
mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
(yes || true) | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses

# Proto deps
buildscripts/make_dependencies.sh

# Build Android with Java 11, this adds it to the PATH
sudo update-java-alternatives --set java-1.11.0-openjdk-amd64
# Unset any existing JAVA_HOME env var to stop Gradle from using it
unset JAVA_HOME

GRADLE_FLAGS="-Pandroid.useAndroidX=true"

./gradlew \
    :grpc-android-interop-testing:build \
    :grpc-android:build \
    :grpc-cronet:build \
    :grpc-binder:build \
    assembleAndroidTest \
    publishToMavenLocal \
    $GRADLE_FLAGS

if [[ ! -z $(git status --porcelain) ]]; then
  git status
  echo "Error Working directory is not clean. Forget to commit generated files?"
  exit 1
fi

# Build examples

cd ./examples/android/clientcache
../../gradlew build $GRADLE_FLAGS
cd ../routeguide
../../gradlew build $GRADLE_FLAGS
cd ../helloworld
../../gradlew build $GRADLE_FLAGS
cd ../strictmode
../../gradlew build $GRADLE_FLAGS

# Skip APK size and dex count comparisons for non-PR builds

if [[ -z "${KOKORO_GITHUB_PULL_REQUEST_COMMIT:-}" ]]; then
    echo "Skipping APK size and dex count"
    exit 0
fi

# Collect APK size and dex count stats for the helloworld example
HELLO_WORLD_OUTPUT_DIR="$BASE_DIR/github/grpc-java/examples/android/helloworld/app/build/outputs"

# Install dependencies of apkanalyzer
"${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;35.0.0"

read -r ignored new_dex_count < \
  <("${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer" dex references \
  "$HELLO_WORLD_OUTPUT_DIR/apk/release/app-release-unsigned.apk")

set +x
all_new_methods=`"${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer" dex packages \
  --proguard-mapping "$HELLO_WORLD_OUTPUT_DIR/mapping/release/mapping.txt" \
  "$HELLO_WORLD_OUTPUT_DIR/apk/release/app-release-unsigned.apk" | grep ^M | cut -f4 | sort`
set -x

new_apk_size="$(stat --printf=%s $HELLO_WORLD_OUTPUT_DIR/apk/release/app-release-unsigned.apk)"


# Get the APK size and dex count stats using the pull request base commit
cd $BASE_DIR/github/grpc-java
./gradlew clean
git checkout HEAD^
./gradlew --stop  # use a new daemon to build the previous commit
./gradlew publishToMavenLocal $GRADLE_FLAGS
cd examples/android/helloworld/
../../gradlew build $GRADLE_FLAGS

read -r ignored old_dex_count < \
  <("${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer" dex references app/build/outputs/apk/release/app-release-unsigned.apk)

set +x
all_old_methods=`"${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer" dex packages --proguard-mapping app/build/outputs/mapping/release/mapping.txt app/build/outputs/apk/release/app-release-unsigned.apk | grep ^M | cut -f4 | sort`
set -x

old_apk_size="$(stat --printf=%s app/build/outputs/apk/release/app-release-unsigned.apk)"

dex_count_delta="$((new_dex_count-old_dex_count))"

apk_size_delta="$((new_apk_size-old_apk_size))"

set +x
dex_method_diff=`diff -u <(echo "$all_old_methods") <(echo "$all_new_methods") || true`
set -x

if [[ -n "$dex_method_diff" ]]
then
  echo "Method diff: ${dex_method_diff}"
fi

# Update the statuses with the deltas

gsutil cp gs://grpc-testing-secrets/github_credentials/oauth_token.txt ~/

desc="New DEX reference count: $(printf "%'d" "$new_dex_count") (delta: $(printf "%'d" "$dex_count_delta"))"
curl -f -s -X POST -H "Content-Type: application/json" \
    -H "Authorization: token $(cat ~/oauth_token.txt | tr -d '\n')" \
    -d '{"state": "success", "context": "android/dex_diff", "description": "'"${desc}"'"}' \
    "https://api.github.com/repos/grpc/grpc-java/statuses/${KOKORO_GITHUB_PULL_REQUEST_COMMIT}"

desc="New APK size in bytes: $(printf "%'d" "$new_apk_size") (delta: $(printf "%'d" "$apk_size_delta"))"
curl -f -s -X POST -H "Content-Type: application/json" \
    -H "Authorization: token $(cat ~/oauth_token.txt | tr -d '\n')" \
    -d '{"state": "success", "context": "android/apk_diff", "description": "'"${desc}"'"}' \
    "https://api.github.com/repos/grpc/grpc-java/statuses/${KOKORO_GITHUB_PULL_REQUEST_COMMIT}"
