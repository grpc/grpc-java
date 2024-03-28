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

# Update VERSION then execute this script

set -e
# import VERSION from one of the google internal CLs
VERSION=dfcdc5ea103dda467963fb7079e4df28debcfd28
DOWNLOAD_URL="https://github.com/envoyproxy/protoc-gen-validate/archive/${VERSION}.tar.gz"
DOWNLOAD_BASE_DIR="protoc-gen-validate-${VERSION}"
SOURCE_PROTO_BASE_DIR="${DOWNLOAD_BASE_DIR}"
TARGET_PROTO_BASE_DIR=src/main/proto
# Sorted alphabetically.
FILES=(
validate/validate.proto
)

pushd `git rev-parse --show-toplevel`/xds/third_party/protoc-gen-validate

# put the repo in a tmp directory
tmpdir="$(mktemp -d)"
trap "rm -rf ${tmpdir}" EXIT
curl -Ls "${DOWNLOAD_URL}" | tar xz -C "${tmpdir}"

cp -p "${tmpdir}/${DOWNLOAD_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${DOWNLOAD_BASE_DIR}/NOTICE" NOTICE

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
