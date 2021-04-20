#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

cd github/grpc-java

buildscripts/qemu_helpers/prepare_qemu.sh

buildscripts/run_arm64_tests_in_docker.sh
