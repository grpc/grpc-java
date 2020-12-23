#!/bin/bash
# Copyright 2018 The gRPC Authors
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
BRANCH=master
# import VERSION from one of the google internal CLs
VERSION=5459f2c994033b0afed7e4a70ac7e90c90c1ffee
GIT_REPO="https://github.com/cncf/udpa.git"
GIT_BASE_DIR=udpa
SOURCE_PROTO_BASE_DIR=udpa
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
udpa/annotations/migrate.proto
udpa/annotations/security.proto
udpa/annotations/sensitive.proto
udpa/annotations/status.proto
udpa/annotations/versioning.proto
udpa/data/orca/v1/orca_load_report.proto
udpa/service/orca/v1/orca.proto
xds/core/v3/authority.proto
xds/core/v3/collection_entry.proto
xds/core/v3/context_params.proto
xds/core/v3/resource_locator.proto
xds/core/v3/resource_name.proto
)

pushd `git rev-parse --show-toplevel`/xds/third_party/udpa

# clone the udpa github repo in a tmp directory
tmpdir="$(mktemp -d)"
trap "rm -rf $tmpdir" EXIT

pushd "${tmpdir}"
git clone -b $BRANCH $GIT_REPO
trap "rm -rf $GIT_BASE_DIR" EXIT
cd "$GIT_BASE_DIR"
git checkout $VERSION
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE

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
