#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

PROTOBUF_VERSION=3.5.1

rm -rf /tmp/source_head/protobuf
mkdir -p /tmp/source_head/protobuf
# Clone from latest until protobuf makes a release with this fix:
# https://github.com/google/protobuf/pull/4447
git clone https://github.com/google/protobuf.git /tmp/source_head/protobuf

cd /tmp/source_head/protobuf
git fetch origin
git checkout origin/master
docker build -t protoc-artifacts protoc-artifacts

cd "$GRPC_JAVA_DIR"/buildscripts/

docker build -t grpc-java-releasing "$GRPC_JAVA_DIR"/buildscripts/grpc-java-releasing

"$GRPC_JAVA_DIR"/buildscripts/run_in_docker.sh /grpc-java/buildscripts/build_artifacts_in_docker.sh
