#!/bin/bash

set -evx -o pipefail

if [ "${TARGET}" = "bazel" ]; then
  mkdir -p /tmp/bazel
  wget -nc "https://github.com/bazelbuild/bazel/releases/download/${BAZEL_VERSION}/bazel_${BAZEL_VERSION}-linux-x86_64.deb" -P /tmp/bazel
  sudo dpkg -i "/tmp/bazel/bazel_${BAZEL_VERSION}-linux-x86_64.deb"
fi

if [ "${TARGET}" = "gradle" ]; then
  mkdir -p $HOME/.gradle/caches &&
    ln -s /tmp/gradle-caches-modules-2 $HOME/.gradle/caches/modules-2
  mkdir -p $HOME/.gradle &&
    ln -s /tmp/gradle-wrapper $HOME/.gradle/wrapper
  # Work around https://github.com/travis-ci/travis-ci/issues/2317
  if [ "${TRAVIS_OS_NAME}" = linux ]; then 
    source /opt/jdk_switcher/jdk_switcher.sh && jdk_switcher use oraclejdk8
  fi
  buildscripts/make_dependencies.sh # build protoc into /tmp/protobuf-${PROTOBUF_VERSION}
  ln -s "/tmp/protobuf-${PROTOBUF_VERSION}/$(uname -s)-$(uname -p)" /tmp/protobuf
  mkdir -p $HOME/.gradle
  echo "checkstyle.ignoreFailures=false" >> $HOME/.gradle/gradle.properties
  echo "failOnWarnings=true" >> $HOME/.gradle/gradle.properties
  echo "errorProne=true" >> $HOME/.gradle/gradle.properties
fi
