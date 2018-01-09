#!/bin/bash

set -evx -o pipefail

if [ "${TARGET}" = "bazel" ]; then
  bazel build //...
fi

if [ "${TARGET}" = "gradle" ]; then
  ./gradlew check :grpc-all:jacocoTestReport
fi
