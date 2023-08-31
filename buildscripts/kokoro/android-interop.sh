#!/bin/bash

set -exu -o pipefail
if [[ -f /VERSION ]]; then
  cat /VERSION
fi

# Install gRPC and codegen for the Android interop app
# (a composite gradle build can't find protoc-gen-grpc-java)

cd github/grpc-java

export GRADLE_OPTS=-Xmx512m
export LDFLAGS=-L/tmp/protobuf/lib
export CXXFLAGS=-I/tmp/protobuf/include
export LD_LIBRARY_PATH=/tmp/protobuf/lib
export OS_NAME=$(uname)

(yes || true) | "${ANDROID_HOME}/tools/bin/sdkmanager" --licenses

# Proto deps
buildscripts/make_dependencies.sh

# Build Android with Java 11, this adds it to the PATH
sudo update-java-alternatives --set java-1.11.0-openjdk-amd64
# Unset any existing JAVA_HOME env var to stop Gradle from using it
unset JAVA_HOME

GRADLE_FLAGS="-Pandroid.useAndroidX=true"

# Build and run interop instrumentation tests on Firebase Test Lab
cd android-interop-testing
../gradlew assembleDebug $GRADLE_FLAGS
../gradlew assembleDebugAndroidTest $GRADLE_FLAGS
gcloud firebase test android run \
  --type instrumentation \
  --app build/outputs/apk/debug/grpc-android-interop-testing-debug.apk \
  --test build/outputs/apk/androidTest/debug/grpc-android-interop-testing-debug-androidTest.apk \
  --environment-variables \
      server_host=grpc-test.sandbox.googleapis.com,server_port=443,test_case=all \
  --device model=Nexus6P,version=27,locale=en,orientation=portrait \
  --device model=Nexus6P,version=26,locale=en,orientation=portrait \
  --device model=Nexus6P,version=25,locale=en,orientation=portrait \
  --device model=Nexus6P,version=24,locale=en,orientation=portrait \
  --device model=Nexus6P,version=23,locale=en,orientation=portrait \
  --device model=Nexus6,version=22,locale=en,orientation=portrait \
  --device model=Nexus6,version=21,locale=en,orientation=portrait

# Build and run binderchannel instrumentation tests on Firebase Test Lab
cd ../binder
../gradlew assembleDebugAndroidTest $GRADLE_FLAGS
gcloud firebase test android run \
  --type instrumentation \
  --app ../android-interop-testing/build/outputs/apk/debug/grpc-android-interop-testing-debug.apk \
  --test build/outputs/apk/androidTest/debug/grpc-binder-debug-androidTest.apk \
  --device model=Nexus6P,version=27,locale=en,orientation=portrait \
  --device model=Nexus6P,version=26,locale=en,orientation=portrait \
  --device model=Nexus6P,version=25,locale=en,orientation=portrait \
  --device model=Nexus6P,version=24,locale=en,orientation=portrait \
  --device model=Nexus6P,version=23,locale=en,orientation=portrait \
  --device model=Nexus6,version=22,locale=en,orientation=portrait \
  --device model=Nexus6,version=21,locale=en,orientation=portrait
