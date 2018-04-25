#!/bin/bash
set -eu -o pipefail

quote() {
  local arg
  for arg in "$@"; do
    printf "'"
    printf "%s" "$arg" | sed -e "s/'/'\\\\''/g"
    printf "' "
  done
}

readonly grpc_java_dir="$(dirname $(readlink -f "$0"))/.."
# We do not use -it because the input device in kokoro is not a TTY
exec docker run --rm=true -v "${grpc_java_dir}:/grpc-java" -w /grpc-java \
  grpc-java-releasing \
  bash -c "$(quote "$@"); chown -R $(id -u):$(id -g) /grpc-java"
