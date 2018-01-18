#!/bin/bash

set -exu -o pipefail
cat /VERSION

BASE_DIR=$(pwd)

# Set up APK size and dex count statuses

gsutil cp gs://grpc-testing-secrets/github_credentials/oauth_token.txt ~/

function set_status_to_fail_on_error {
  cd $BASE_DIR/github/grpc-java

  # TODO(ericgribkoff) Remove once merged
  git checkout $KOKORO_GITHUB_PULL_REQUEST_COMMIT

  ./buildscripts/set_github_status.py \
    --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
    --state error \
    --description "Failed to calculate DEX count" \
    --context android/dex_diff --oauth_file ~/oauth_token.txt
  ./buildscripts/set_github_status.py \
    --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
    --state error \
    --description "Failed to calculate APK size" \
    --context android/apk_diff --oauth_file ~/oauth_token.txt
}
trap set_status_to_fail_on_error ERR

$BASE_DIR/github/grpc-java/buildscripts/set_github_status.py \
  --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
  --state pending \
  --description "Waiting for DEX count to be reported" \
  --context android/dex_diff --oauth_file ~/oauth_token.txt
$BASE_DIR/github/grpc-java/buildscripts/set_github_status.py \
  --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
  --state pending \
  --description "Waiting for APK size to be reported" \
  --context android/apk_diff --oauth_file ~/oauth_token.txt


# Build Cronet

cd $BASE_DIR/github/grpc-java/cronet
./cronet_deps.sh
../gradlew --include-build .. build


# Install gRPC and codegen for the Android examples
# (a composite gradle build can't find protoc-gen-grpc-java)

cd $BASE_DIR/github/grpc-java

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


# Build and collect APK size and dex count stats for the helloworld example

cd ../helloworld
./gradlew build

read -r ignored new_dex_count < \
  <(${ANDROID_HOME}/tools/bin/apkanalyzer dex references app/build/outputs/apk/release/app-release-unsigned.apk)

new_apk_size=$(stat --printf=%s app/build/outputs/apk/release/app-release-unsigned.apk)


# Get the APK size and dex count stats using the target branch

sudo apt-get install -y jq
target_branch=$(curl -s https://api.github.com/repos/grpc/grpc-java/pulls/$KOKORO_GITHUB_PULL_REQUEST_NUMBER | jq -r .base.ref)

cd $BASE_DIR/github/grpc-java
git checkout $target_branch
./gradlew install
cd examples/android/helloworld/
./gradlew build

read -r ignored old_dex_count < \
  <(${ANDROID_HOME}/tools/bin/apkanalyzer dex references app/build/outputs/apk/release/app-release-unsigned.apk)

old_apk_size=$(stat --printf=%s app/build/outputs/apk/release/app-release-unsigned.apk)

dex_count_delta=$((new_dex_count-old_dex_count))

apk_size_delta=$((new_apk_size-old_apk_size))


# Update the statuses with the deltas

# TODO(ericgribkoff) Remove checkout once merged
git checkout $KOKORO_GITHUB_PULL_REQUEST_COMMIT

../../../buildscripts/set_github_status.py \
  --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
  --state success \
  --description "New DEX reference count: $new_dex_count (delta: $dex_count_delta)" \
  --context android/dex_diff --oauth_file ~/oauth_token.txt

../../../buildscripts/set_github_status.py \
  --sha1 $KOKORO_GITHUB_PULL_REQUEST_COMMIT \
  --state success \
  --description "New APK size in bytes: $new_apk_size (delta: $apk_size_delta)" \
  --context android/apk_diff --oauth_file ~/oauth_token.txt
