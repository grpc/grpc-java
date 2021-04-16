#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

BASE_DIR="$(pwd)"

# Install gRPC and codegen for the Android examples
# (a composite gradle build can't find protoc-gen-grpc-java)

cd "$BASE_DIR/github/grpc-java"

buildscripts/qemu_helpers/prepare_qemu.sh

buildscripts/run_arm64_tests_in_docker.sh
