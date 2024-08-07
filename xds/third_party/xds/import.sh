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
VERSION=024c85f92f20cab567a83acc50934c7f9711d124
DOWNLOAD_URL="https://github.com/cncf/xds/archive/${VERSION}.tar.gz"
DOWNLOAD_BASE_DIR="xds-${VERSION}"
SOURCE_PROTO_BASE_DIR="${DOWNLOAD_BASE_DIR}"
TARGET_PROTO_BASE_DIR=src/main/proto
# Sorted alphabetically.
FILES=(
udpa/annotations/migrate.proto
udpa/annotations/security.proto
udpa/annotations/security.proto
udpa/annotations/sensitive.proto
udpa/annotations/status.proto
udpa/annotations/versioning.proto
udpa/type/v1/typed_struct.proto
xds/annotations/v3/migrate.proto
xds/annotations/v3/security.proto
xds/annotations/v3/security.proto
xds/annotations/v3/sensitive.proto
xds/annotations/v3/status.proto
xds/annotations/v3/versioning.proto
xds/core/v3/authority.proto
xds/core/v3/collection_entry.proto
xds/core/v3/context_params.proto
xds/core/v3/extension.proto
xds/core/v3/resource_locator.proto
xds/core/v3/resource_name.proto
xds/data/orca/v3/orca_load_report.proto
xds/service/orca/v3/orca.proto
xds/type/matcher/v3/cel.proto
xds/type/matcher/v3/matcher.proto
xds/type/matcher/v3/regex.proto
xds/type/matcher/v3/string.proto
xds/type/v3/cel.proto
xds/type/matcher/v3/http_inputs.proto
xds/type/v3/typed_struct.proto
)

pushd `git rev-parse --show-toplevel`/xds/third_party/xds

# put the repo in a tmp directory
tmpdir="$(mktemp -d)"
trap "rm -rf ${tmpdir}" EXIT
curl -Ls "${DOWNLOAD_URL}" | tar xz -C "${tmpdir}"

cp -p "${tmpdir}/${DOWNLOAD_BASE_DIR}/LICENSE" LICENSE

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
