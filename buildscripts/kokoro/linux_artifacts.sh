#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

. "$GRPC_JAVA_DIR"/buildscripts/kokoro/kokoro.sh
trap spongify_logs EXIT

"$GRPC_JAVA_DIR"/buildscripts/build_docker.sh
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-x86 /grpc-java/buildscripts/build_artifacts_in_docker.sh

"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-multiarch env \
  SKIP_TESTS=true ARCH=aarch_64 /grpc-java/buildscripts/kokoro/unix.sh
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-multiarch env \
  SKIP_TESTS=true ARCH=ppcle_64 /grpc-java/buildscripts/kokoro/unix.sh
# Use a newer GCC version. GCC 7 in multiarch has a bug:
#   internal compiler error: output_operand: invalid %-code
"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh grpc-java-artifacts-ubuntu2004 env \
  SKIP_TESTS=true ARCH=s390_64 /grpc-java/buildscripts/kokoro/unix.sh
