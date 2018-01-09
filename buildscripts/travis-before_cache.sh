#!/bin/bash

set -evx -o pipefail

if [ "${TARGET}" = "bazel" ]; then
  echo 'NOOP'
fi

if [ "${TARGET}" = "gradle" ]; then
  # The lock changes based on folder name; normally $HOME/.gradle/caches/modules-2/modules-2.lock
  rm /tmp/gradle-caches-modules-2/gradle-caches-modules-2.lock
  find $HOME/.gradle/wrapper -not -name "*-all.zip" -and -not -name "*-bin.zip" -delete
fi
