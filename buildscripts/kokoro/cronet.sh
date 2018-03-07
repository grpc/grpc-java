#!/bin/bash

set -exu -o pipefail
cat /VERSION

BASE_DIR="$(pwd)"

# Build Cronet

cd "$BASE_DIR/github/grpc-java/cronet"
./cronet_deps.sh
../gradlew --include-build .. build


# Install gRPC and codegen for the Android examples
# (a composite gradle build can't find protoc-gen-grpc-java)

cd "$BASE_DIR/github/grpc-java"

export GRADLE_OPTS=-Xmx512m
export PROTOBUF_VERSION=3.5.1
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS=-I/tmp/protobuf/include
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export OS_NAME=$(uname)

# Proto deps
buildscripts/make_dependencies.sh
ln -s "/tmp/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)" /tmp/protobuf

./gradlew install

cd ./examples/android/clientcache
./gradlew build
cd ../routeguide
./gradlew build
cd ../helloworld
./gradlew build


# Skip APK size and dex count comparisons for non-PR builds

if [[ -z "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" ]]; then
    echo "Skipping APK size and dex count"
    exit 0
fi


# Set up APK size and dex count statuses

SET_GITHUB_STATUS="$TMPDIR/set_github_status.py"
cp "$BASE_DIR/github/grpc-java/buildscripts/set_github_status.py" "$SET_GITHUB_STATUS"

gsutil cp gs://grpc-testing-secrets/github_credentials/oauth_token.txt ~/

function set_status_to_fail_on_error {
  "$SET_GITHUB_STATUS" \
    --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
    --state error \
    --description "Failed to calculate DEX count" \
    --context android/dex_diff --oauth_file ~/oauth_token.txt
  "$SET_GITHUB_STATUS" \
    --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
    --state error \
    --description "Failed to calculate APK size" \
    --context android/apk_diff --oauth_file ~/oauth_token.txt
}
trap set_status_to_fail_on_error ERR


"$SET_GITHUB_STATUS" \
  --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
  --state pending \
  --description "Waiting for DEX count to be reported" \
  --context android/dex_diff --oauth_file ~/oauth_token.txt
"$SET_GITHUB_STATUS" \
  --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
  --state pending \
  --description "Waiting for APK size to be reported" \
  --context android/apk_diff --oauth_file ~/oauth_token.txt

# Collect APK size and dex count stats for the helloworld example

read -r ignored new_dex_count < \
  <("${ANDROID_HOME}/tools/bin/apkanalyzer" dex references app/build/outputs/apk/release/app-release-unsigned.apk)

new_apk_size="$(stat --printf=%s app/build/outputs/apk/release/app-release-unsigned.apk)"


# Get the APK size and dex count stats using the target branch

base_commit="$(git rev-parse HEAD^)"

cd $BASE_DIR/github/grpc-java
git checkout "$base_commit"
./gradlew install
cd examples/android/helloworld/
./gradlew build

read -r ignored old_dex_count < \
  <("${ANDROID_HOME}/tools/bin/apkanalyzer" dex references app/build/outputs/apk/release/app-release-unsigned.apk)

old_apk_size="$(stat --printf=%s app/build/outputs/apk/release/app-release-unsigned.apk)"

dex_count_delta="$((new_dex_count-old_dex_count))"

apk_size_delta="$((new_apk_size-old_apk_size))"


# Update the statuses with the deltas

"$SET_GITHUB_STATUS" \
  --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
  --state success \
  --description "New DEX reference count: $(printf "%'d" "$new_dex_count") (delta: $(printf "%'d" "$dex_count_delta"))" \
  --context android/dex_diff --oauth_file ~/oauth_token.txt

"$SET_GITHUB_STATUS" \
  --sha1 "$KOKORO_GITHUB_PULL_REQUEST_COMMIT" \
  --state success \
  --description "New APK size in bytes: $(printf "%'d" "$new_apk_size") (delta: $(printf "%'d" "$apk_size_delta"))" \
  --context android/apk_diff --oauth_file ~/oauth_token.txt
