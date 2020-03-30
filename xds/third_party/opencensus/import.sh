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

# Update GIT_ORIGIN_REV_ID then in this directory run ./import.sh

set -e
BRANCH=master
# import GIT_ORIGIN_REV_ID be218fb6bd674af7519b1850cdf8410d8cbd48e8
GIT_ORIGIN_REV_ID=be218fb6bd674af7519b1850cdf8410d8cbd48e8
GIT_REPO="https://github.com/census-instrumentation/opencensus-proto"
GIT_BASE_DIR=opencensus-proto
SOURCE_PROTO_BASE_DIR=opencensus-proto
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
opencensus/proto/trace/v1/trace_config.proto
)

# clone the opencensus-proto github repo in a tmp directory
tmpdir="$(mktemp -d)"
pushd "${tmpdir}"
rm -rf "$GIT_BASE_DIR"
git clone -b $BRANCH $GIT_REPO
cd "$GIT_BASE_DIR"
git checkout $GIT_ORIGIN_REV_ID
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${GIT_BASE_DIR}/AUTHORS" AUTHORS

mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}"

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/src/${file}" "${file}"
done
popd

rm -rf "$tmpdir"
