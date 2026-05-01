#!/bin/bash
set -eu -o pipefail

readonly buildscripts_dir="$(dirname "$(readlink -f "$0")")"
docker build -t grpc-java-artifacts-x86 "$buildscripts_dir"/grpc-java-artifacts
docker build -t grpc-java-artifacts-multiarch -f "$buildscripts_dir"/grpc-java-artifacts/Dockerfile.multiarch.base "$buildscripts_dir"/grpc-java-artifacts
docker build -t grpc-java-artifacts-ubuntu2004 -f "$buildscripts_dir"/grpc-java-artifacts/Dockerfile.ubuntu2004.base "$buildscripts_dir"/grpc-java-artifacts

