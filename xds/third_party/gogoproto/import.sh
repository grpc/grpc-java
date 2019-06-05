#!/bin/bash
# Copyright 2019 The gRPC Authors
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

# Update BRANCH then in this directory run ./import.sh

set -e
BRANCH=v1.2.0
GIT_REPO="https://github.com/gogo/protobuf.git"
GIT_BASE_DIR=protobuf
SOURCE_PROTO_BASE_DIR=protobuf
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
gogoproto/gogo.proto
)

# clone the gogoproto github repo in a tmp directory
tmpdir="$(mktemp -d)"
pushd "${tmpdir}"
rm -rf "$GIT_BASE_DIR"
git clone -b $BRANCH $GIT_REPO
cd "$GIT_BASE_DIR"
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE

mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}"

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}"
done
popd

rm -rf "$tmpdir"
