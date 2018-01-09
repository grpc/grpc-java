#!/bin/bash

set -evx -o pipefail

if [ "${TARGET}" = "bazel" ]; then
  echo 'NOOP'
fi

if [ "${TARGET}" = "gradle" ]; then
  if [ "$TRAVIS_OS_NAME" = linux ]; then 
    ./gradlew :grpc-all:coveralls
  fi
  bash <(curl -s https://codecov.io/bash)
fi
