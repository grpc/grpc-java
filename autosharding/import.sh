#!/bin/bash
# Copyright 2026 The gRPC Authors
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
VERSION=3a21f69fe5516fe340af29ef68e327381473ecc7
DOWNLOAD_URL="https://github.com/GoogleCloudPlatform/autosharding/archive/${VERSION}.tar.gz"
DOWNLOAD_BASE_DIR="autosharding-${VERSION}"
SOURCE_PROTO_BASE_DIR="${DOWNLOAD_BASE_DIR}/proto"
TARGET_PROTO_BASE_DIR="src/main/proto"
# Sorted alphabetically.
FILES=(
autosharding/v1/autosharding.proto
)

pushd "$(git rev-parse --show-toplevel)/autosharding" > /dev/null

# put the repo in a tmp directory
tmpdir="$(mktemp -d)"
trap "rm -rf ${tmpdir}" EXIT
curl -Ls "${DOWNLOAD_URL}" | tar xz -C "${tmpdir}"

rm -rf "${TARGET_PROTO_BASE_DIR}"
mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}" > /dev/null

# copy proto files to project directory
TOTAL=${#FILES[@]}
COPIED=0
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}" && (( ++COPIED ))
done
popd > /dev/null

popd > /dev/null

echo "Imported ${COPIED} files."
if (( COPIED != TOTAL )); then
  echo "Failed importing $(( TOTAL - COPIED )) files." 1>&2
  exit 1
fi
