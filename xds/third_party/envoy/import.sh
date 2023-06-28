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
BRANCH=main
# import VERSION from the google internal copybara_version.txt for Envoy
VERSION=0478eba2a495027bf6ac8e787c42e2f5b9eb553b
GIT_REPO="https://github.com/envoyproxy/envoy.git"
GIT_BASE_DIR=envoy
SOURCE_PROTO_BASE_DIR=envoy/api
TARGET_PROTO_BASE_DIR=src/main/proto
# Sorted alphabetically.
FILES=(
envoy/admin/v3/config_dump.proto
envoy/admin/v3/config_dump_shared.proto
envoy/annotations/deprecation.proto
envoy/annotations/resource.proto
envoy/api/v2/auth/cert.proto
envoy/api/v2/auth/common.proto
envoy/api/v2/auth/secret.proto
envoy/api/v2/auth/tls.proto
envoy/api/v2/cds.proto
envoy/api/v2/cluster.proto
envoy/api/v2/cluster/circuit_breaker.proto
envoy/api/v2/cluster/filter.proto
envoy/api/v2/cluster/outlier_detection.proto
envoy/api/v2/core/address.proto
envoy/api/v2/core/backoff.proto
envoy/api/v2/core/base.proto
envoy/api/v2/core/config_source.proto
envoy/api/v2/core/event_service_config.proto
envoy/api/v2/core/grpc_service.proto
envoy/api/v2/core/health_check.proto
envoy/api/v2/core/http_uri.proto
envoy/api/v2/core/protocol.proto
envoy/api/v2/core/socket_option.proto
envoy/api/v2/discovery.proto
envoy/api/v2/eds.proto
envoy/api/v2/endpoint.proto
envoy/api/v2/endpoint/endpoint.proto
envoy/api/v2/endpoint/endpoint_components.proto
envoy/api/v2/endpoint/load_report.proto
envoy/api/v2/lds.proto
envoy/api/v2/listener.proto
envoy/api/v2/listener/listener.proto
envoy/api/v2/listener/listener_components.proto
envoy/api/v2/listener/udp_listener_config.proto
envoy/api/v2/rds.proto
envoy/api/v2/route.proto
envoy/api/v2/route/route.proto
envoy/api/v2/route/route_components.proto
envoy/api/v2/scoped_route.proto
envoy/api/v2/srds.proto
envoy/config/accesslog/v3/accesslog.proto
envoy/config/bootstrap/v3/bootstrap.proto
envoy/config/cluster/aggregate/v2alpha/cluster.proto
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
envoy/config/filter/accesslog/v2/accesslog.proto
envoy/config/filter/fault/v2/fault.proto
envoy/config/filter/http/fault/v2/fault.proto
envoy/config/filter/http/router/v2/router.proto
envoy/config/filter/network/http_connection_manager/v2/http_connection_manager.proto
envoy/config/listener/v2/api_listener.proto
envoy/config/listener/v3/api_listener.proto
envoy/config/listener/v3/listener.proto
envoy/config/listener/v3/listener_components.proto
envoy/config/listener/v3/quic_config.proto
envoy/config/listener/v3/udp_listener_config.proto
envoy/config/metrics/v3/stats.proto
envoy/config/overload/v3/overload.proto
envoy/config/rbac/v2/rbac.proto
envoy/config/rbac/v3/rbac.proto
envoy/config/route/v3/route.proto
envoy/config/route/v3/route_components.proto
envoy/config/route/v3/scoped_route.proto
envoy/config/trace/v2/datadog.proto
envoy/config/trace/v2/dynamic_ot.proto
envoy/config/trace/v2/http_tracer.proto
envoy/config/trace/v2/lightstep.proto
envoy/config/trace/v2/opencensus.proto
envoy/config/trace/v2/service.proto
envoy/config/trace/v2/trace.proto
envoy/config/trace/v2/zipkin.proto
envoy/config/trace/v3/datadog.proto
envoy/config/trace/v3/dynamic_ot.proto
envoy/config/trace/v3/http_tracer.proto
envoy/config/trace/v3/lightstep.proto
envoy/config/trace/v3/opencensus.proto
envoy/config/trace/v3/opentelemetry.proto
envoy/config/trace/v3/service.proto
envoy/config/trace/v3/trace.proto
envoy/config/trace/v3/zipkin.proto
envoy/extensions/clusters/aggregate/v3/cluster.proto
envoy/extensions/filters/common/fault/v3/fault.proto
envoy/extensions/filters/http/fault/v3/fault.proto
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
envoy/service/discovery/v2/ads.proto
envoy/service/discovery/v2/sds.proto
envoy/service/discovery/v3/ads.proto
envoy/service/discovery/v3/discovery.proto
envoy/service/load_stats/v2/lrs.proto
envoy/service/load_stats/v3/lrs.proto
envoy/service/status/v3/csds.proto
envoy/type/http.proto
envoy/type/http/v3/path_transformation.proto
envoy/type/matcher/metadata.proto
envoy/type/matcher/number.proto
envoy/type/matcher/path.proto
envoy/type/matcher/regex.proto
envoy/type/matcher/string.proto
envoy/type/matcher/v3/filter_state.proto
envoy/type/matcher/v3/metadata.proto
envoy/type/matcher/v3/node.proto
envoy/type/matcher/v3/number.proto
envoy/type/matcher/v3/path.proto
envoy/type/matcher/v3/regex.proto
envoy/type/matcher/v3/string.proto
envoy/type/matcher/v3/struct.proto
envoy/type/matcher/v3/value.proto
envoy/type/matcher/value.proto
envoy/type/metadata/v2/metadata.proto
envoy/type/metadata/v3/metadata.proto
envoy/type/percent.proto
envoy/type/range.proto
envoy/type/semantic_version.proto
envoy/type/tracing/v2/custom_tag.proto
envoy/type/tracing/v3/custom_tag.proto
envoy/type/v3/http.proto
envoy/type/v3/percent.proto
envoy/type/v3/range.proto
envoy/type/v3/semantic_version.proto
)

pushd `git rev-parse --show-toplevel`/xds/third_party/envoy

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
