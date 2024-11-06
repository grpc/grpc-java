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
# import VERSION from the google internal copybara_version.txt for Envoy
VERSION=742a3b02e3b2a9dfb877a7e378607c6ed0c2aa53
DOWNLOAD_URL="https://github.com/envoyproxy/envoy/archive/${VERSION}.tar.gz"
DOWNLOAD_BASE_DIR="envoy-${VERSION}"
SOURCE_PROTO_BASE_DIR="${DOWNLOAD_BASE_DIR}/api"
TARGET_PROTO_BASE_DIR=src/main/proto
# Sorted alphabetically.
FILES=(
envoy/admin/v3/config_dump.proto
envoy/admin/v3/config_dump_shared.proto
envoy/annotations/deprecation.proto
envoy/config/accesslog/v3/accesslog.proto
envoy/config/bootstrap/v3/bootstrap.proto
envoy/config/cluster/v3/circuit_breaker.proto
envoy/config/cluster/v3/cluster.proto
envoy/config/cluster/v3/filter.proto
envoy/config/cluster/v3/outlier_detection.proto
envoy/config/core/v3/address.proto
envoy/config/core/v3/backoff.proto
envoy/config/core/v3/base.proto
envoy/config/core/v3/config_source.proto
envoy/config/core/v3/event_service_config.proto
envoy/config/core/v3/extension.proto
envoy/config/core/v3/grpc_service.proto
envoy/config/core/v3/health_check.proto
envoy/config/core/v3/http_service.proto
envoy/config/core/v3/http_uri.proto
envoy/config/core/v3/protocol.proto
envoy/config/core/v3/proxy_protocol.proto
envoy/config/core/v3/resolver.proto
envoy/config/core/v3/socket_option.proto
envoy/config/core/v3/substitution_format_string.proto
envoy/config/core/v3/udp_socket_config.proto
envoy/config/endpoint/v3/endpoint.proto
envoy/config/endpoint/v3/endpoint_components.proto
envoy/config/endpoint/v3/load_report.proto
envoy/config/listener/v3/api_listener.proto
envoy/config/listener/v3/listener.proto
envoy/config/listener/v3/listener_components.proto
envoy/config/listener/v3/quic_config.proto
envoy/config/listener/v3/udp_listener_config.proto
envoy/config/metrics/v3/stats.proto
envoy/config/overload/v3/overload.proto
envoy/config/rbac/v3/rbac.proto
envoy/config/route/v3/route.proto
envoy/config/route/v3/route_components.proto
envoy/config/route/v3/scoped_route.proto
envoy/config/trace/v3/datadog.proto
envoy/config/trace/v3/dynamic_ot.proto
envoy/config/trace/v3/http_tracer.proto
envoy/config/trace/v3/lightstep.proto
envoy/config/trace/v3/opentelemetry.proto
envoy/config/trace/v3/service.proto
envoy/config/trace/v3/zipkin.proto
envoy/data/accesslog/v3/accesslog.proto
envoy/extensions/clusters/aggregate/v3/cluster.proto
envoy/extensions/filters/common/fault/v3/fault.proto
envoy/extensions/filters/http/fault/v3/fault.proto
envoy/extensions/filters/http/rate_limit_quota/v3/rate_limit_quota.proto
envoy/extensions/filters/http/rbac/v3/rbac.proto
envoy/extensions/filters/http/router/v3/router.proto
envoy/extensions/filters/network/http_connection_manager/v3/http_connection_manager.proto
envoy/extensions/load_balancing_policies/client_side_weighted_round_robin/v3/client_side_weighted_round_robin.proto
envoy/extensions/load_balancing_policies/common/v3/common.proto
envoy/extensions/load_balancing_policies/least_request/v3/least_request.proto
envoy/extensions/load_balancing_policies/pick_first/v3/pick_first.proto
envoy/extensions/load_balancing_policies/ring_hash/v3/ring_hash.proto
envoy/extensions/load_balancing_policies/round_robin/v3/round_robin.proto
envoy/extensions/load_balancing_policies/wrr_locality/v3/wrr_locality.proto
envoy/extensions/transport_sockets/tls/v3/cert.proto
envoy/extensions/transport_sockets/tls/v3/common.proto
envoy/extensions/transport_sockets/tls/v3/secret.proto
envoy/extensions/transport_sockets/tls/v3/tls.proto
envoy/service/discovery/v3/ads.proto
envoy/service/discovery/v3/discovery.proto
envoy/service/load_stats/v3/lrs.proto
envoy/service/rate_limit_quota/v3/rlqs.proto
envoy/service/status/v3/csds.proto
envoy/type/http/v3/path_transformation.proto
envoy/type/matcher/v3/filter_state.proto
envoy/type/matcher/v3/http_inputs.proto
envoy/type/matcher/v3/metadata.proto
envoy/type/matcher/v3/node.proto
envoy/type/matcher/v3/number.proto
envoy/type/matcher/v3/path.proto
envoy/type/matcher/v3/regex.proto
envoy/type/matcher/v3/string.proto
envoy/type/matcher/v3/struct.proto
envoy/type/matcher/v3/value.proto
envoy/type/metadata/v3/metadata.proto
envoy/type/tracing/v3/custom_tag.proto
envoy/type/v3/http.proto
envoy/type/v3/http_status.proto
envoy/type/v3/percent.proto
envoy/type/v3/range.proto
envoy/type/v3/ratelimit_strategy.proto
envoy/type/v3/ratelimit_unit.proto
envoy/type/v3/semantic_version.proto
envoy/type/v3/token_bucket.proto
)

pushd "$(git rev-parse --show-toplevel)/xds/third_party/envoy" > /dev/null

# put the repo in a tmp directory
tmpdir="$(mktemp -d)"
echo "Using tmp dir: ${tmpdir}"

# Intentionally expand at the execution time rather than when signalled.
# shellcheck disable=SC2064
trap "rm -rf ${tmpdir}" EXIT

curl -Ls "${DOWNLOAD_URL}" | tar xz -C "${tmpdir}"

cp -p "${tmpdir}/${DOWNLOAD_BASE_DIR}/LICENSE" LICENSE
cp -p "${tmpdir}/${DOWNLOAD_BASE_DIR}/NOTICE" NOTICE

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
