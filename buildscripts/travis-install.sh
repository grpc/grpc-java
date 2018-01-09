#!/bin/bash

set -evx -o pipefail

if [ "${TARGET}" = "bazel" ]; then
  echo 'NOOP'
fi

if [ "${TARGET}" = "gradle" ]; then
  ./gradlew assemble generateTestProto install
  pushd examples && ./gradlew build && popd
  pushd examples && mvn verify && popd
fi
