#!/bin/bash
set -eux -o pipefail

quote() {
  local arg
  for arg in "$@"; do
    printf "'"
    printf "%s" "$arg" | sed -e "s/'/'\\\\''/g"
    printf "' "
  done
}

readonly grpc_java_dir="$(dirname "$(readlink -f "$0")")/.."
if [[ -t 0 ]]; then
  DOCKER_ARGS="-it"
else
  # The input device on kokoro is not a TTY, so -it does not work.
  DOCKER_ARGS=""
fi
# Use a trap function to fix file permissions upon exit, without affecting
# the original exit code.
exec docker run "$DOCKER_ARGS" --rm=true -v "${grpc_java_dir}":/grpc-java -w /grpc-java \
  grpc-java-releasing \
  bash -c "function fixFiles() { chown -R $(id -u):$(id -g) /grpc-java; }; trap fixFiles EXIT; $(quote "$@")"
