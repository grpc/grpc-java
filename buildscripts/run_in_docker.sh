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
exec docker run -it --rm=true -v "${grpc_java_dir}:/grpc-java" -w /grpc-java \
  grpc-java-releasing \
  bash -c "$(quote "$@"); chown -R $(id -u):$(id -g) /grpc-java"
