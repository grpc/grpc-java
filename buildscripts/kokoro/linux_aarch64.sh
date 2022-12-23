#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

. "$GRPC_JAVA_DIR"/buildscripts/kokoro/kokoro.sh
trap spongify_logs EXIT

cd github/grpc-java

buildscripts/qemu_helpers/prepare_qemu.sh

buildscripts/run_arm64_tests_in_docker.sh
