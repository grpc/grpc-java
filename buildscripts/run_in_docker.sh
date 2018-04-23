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

readonly GRPC_JAVA_DIR="$(dirname $(readlink -f "$0"))/.."
readonly USER=$(whoami)
cd $GRPC_JAVA_DIR/buildscripts/
docker build -t grpc-java-releasing-$USER grpc-java-releasing \
  --build-arg UID=$(id -u) --build-arg GID=$(id -g) \
  --build-arg USERNAME=$USER

exec docker run -it --user $USER --rm=true -v \
 "${GRPC_JAVA_DIR}:/grpc-java" -w /grpc-java \
 grpc-java-releasing-$USER \
 /bin/bash -c "$(quote "$@")"
