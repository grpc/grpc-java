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

# Only run this script on Linux environment.

# Update VERSION then in this directory run ./import.sh

set -e
BRANCH=master
# import VERSION from one of the google internal CLs
VERSION=228a963d1308eb1b06e2e8b7387e0bfa72fe77ea
GIT_REPO="https://github.com/envoyproxy/envoy.git"
GIT_BASE_DIR=envoy
SOURCE_PROTO_BASE_DIR=envoy/api
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
udpa/data/orca/v1/orca_load_report.proto
udpa/service/orca/v1/orca.proto
envoy/api/v2/auth/cert.proto
envoy/api/v2/cds.proto
envoy/api/v2/cluster/circuit_breaker.proto
envoy/api/v2/cluster/outlier_detection.proto
envoy/api/v2/core/address.proto
envoy/api/v2/core/base.proto
envoy/api/v2/core/config_source.proto
envoy/api/v2/core/grpc_service.proto
envoy/api/v2/core/health_check.proto
envoy/api/v2/core/protocol.proto
envoy/api/v2/discovery.proto
envoy/api/v2/eds.proto
envoy/api/v2/endpoint/endpoint.proto
envoy/api/v2/endpoint/load_report.proto
envoy/service/discovery/v2/ads.proto
envoy/service/load_stats/v2/lrs.proto
envoy/type/percent.proto
envoy/type/range.proto
)

# clone the envoy github repo in a tmp directory
tmpdir="$(mktemp -d)"
pushd "${tmpdir}"
rm -rf $GIT_BASE_DIR
git clone -b $BRANCH $GIT_REPO
cd "$GIT_BASE_DIR"
git checkout $VERSION
popd

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${GIT_BASE_DIR}/NOTICE" NOTICE

mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}"

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}"
done

# DO NOT TOUCH! The following section is upstreamed with an internal script.

# See google internal third_party/envoy/envoy-update.sh
# ===========================================================================
# Fix up proto imports and remove references to gogoproto.
# ===========================================================================
for f in "${FILES[@]}"
do
  commands=(
    # Import mangling.
    -e 's#import "gogoproto/gogo.proto";##'
    # Remove references to gogo.proto extensions.
    -e 's#option (gogoproto\.[a-z_]\+) = \(true\|false\);##'
    -e 's#\(, \)\?(gogoproto\.[a-z_]\+) = \(true\|false\),\?##'
    # gogoproto removal can result in empty brackets.
    -e 's# \[\]##'
    # gogoproto removal can result in four spaces on a line by itself.
    -e '/^    $/d'
  )
  sed -i "${commands[@]}" "$f"

  # gogoproto removal can leave a comma on the last element in a list.
  # This needs to run separately after all the commands above have finished
  # since it is multi-line and rewrites the output of the above patterns.
  sed -i -e '$!N; s#\(.*\),\([[:space:]]*\];\)#\1\2#; t; P; D;' "$f"
done
popd

rm -rf "$tmpdir"
