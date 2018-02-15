#!/bin/bash
set -e

quote() {
  for X in "$@"; do
    printf "'"
    printf "%s" "$X" | sed -e "s/'/'\\\\''/g"
    printf "' "
  done
}

if [ "$1" != "in-docker" ]; then
  args="$(quote "$@")"
  exec docker run -it --rm=true -v $PWD:/grpc-java grpc-java-deploy \
    "/grpc-java/buildscripts/run_in_docker.sh in-docker $(id -u) $(id -g) $args"
fi

shift
# In Docker
SWAP_UID=$1
SWAP_GID=$2
shift 2
# Java uses NSS to determine the user's home. If that fails it uses '?' in the
# current directory. So we need to set up the user's home in /etc/passwd.
groupadd thegroup -g $SWAP_GID
useradd theuser -u $SWAP_UID -g $SWAP_GID -m
cd /grpc-java
args="$(quote "$@")"
exec su theuser -c "$args"
