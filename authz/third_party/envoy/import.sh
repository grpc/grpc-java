#!/bin/bash
# Copyright 2021 The gRPC Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Update VERSION then in this directory run ./import.sh

set -e
BRANCH=main
# import VERSION from one of the google internal CLs
VERSION=c223756b0856f734a6a5cff2d0b95388cd2583d4
GIT_REPO="https://github.com/envoyproxy/envoy.git"
GIT_BASE_DIR=envoy
SOURCE_PROTO_BASE_DIR=envoy/api
TARGET_PROTO_BASE_DIR=src/main/proto
# Sorted alphabetically.
FILES=(
envoy/annotations/deprecation.proto
envoy/config/core/v3/address.proto
envoy/config/core/v3/backoff.proto
envoy/config/core/v3/base.proto
envoy/config/core/v3/config_source.proto
envoy/config/core/v3/extension.proto
envoy/config/core/v3/grpc_service.proto
envoy/config/core/v3/http_uri.proto
envoy/config/core/v3/socket_option.proto
envoy/config/rbac/v3/rbac.proto
envoy/config/route/v3/route_components.proto
envoy/type/matcher/v3/metadata.proto
envoy/type/matcher/v3/path.proto
envoy/type/matcher/v3/number.proto
envoy/type/matcher/v3/path.proto
envoy/config/core/v3/proxy_protocol.proto
envoy/type/matcher/v3/regex.proto
envoy/type/matcher/v3/string.proto
envoy/type/matcher/v3/value.proto
envoy/type/metadata/v3/metadata.proto
envoy/type/tracing/v3/custom_tag.proto
envoy/type/v3/percent.proto
envoy/type/v3/range.proto
envoy/type/v3/semantic_version.proto
)

pushd `git rev-parse --show-toplevel`/authz/third_party/envoy

# clone the envoy github repo in a tmp directory
tmpdir="$(mktemp -d)"
trap "rm -rf ${tmpdir}" EXIT

pushd "${tmpdir}"
git clone -b $BRANCH $GIT_REPO
trap "rm -rf $GIT_BASE_DIR" EXIT
cd "$GIT_BASE_DIR"
git checkout $VERSION
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${GIT_BASE_DIR}/NOTICE" NOTICE

rm -rf "${TARGET_PROTO_BASE_DIR}"
mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}"

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}"
done
popd

popd
