#!/bin/bash
set -eu -o pipefail

readonly buildscripts_dir="$(dirname "$(readlink -f "$0")")"
docker build -t grpc-java-artifacts "$buildscripts_dir"/grpc-java-artifacts
