#!/bin/bash

set -exu -o pipefail
if [[ -f /VERSION ]]; then
  cat /VERSION
fi

cd github

pushd grpc-java/interop-testing
GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1g'" \
  ../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
popd

git clone -b master --single-branch --depth=1 https://github.com/grpc/grpc.git

grpc/tools/run_tests/helper_scripts/prep_xds.sh

JAVA_OPTS=-Djava.util.logging.config.file=grpc-java/buildscripts/xds_logging.properties \
  python3 grpc/tools/run_tests/run_xds_tests.py \
    --test_case="ping_pong,circuit_breaking" \
    --project_id=grpc-testing \
    --project_num=830293263384 \
    --source_image=projects/grpc-testing/global/images/xds-test-server-5 \
    --path_to_server_binary=/java_server/grpc-java/interop-testing/build/install/grpc-interop-testing/bin/xds-test-server \
    --gcp_suffix=$(date '+%s') \
    --verbose \
    --xds_v3_support \
    --client_cmd="grpc-java/interop-testing/build/install/grpc-interop-testing/bin/xds-test-client \
      --server=xds:///{server_uri} \
      --stats_port={stats_port} \
      --qps={qps} \
      {rpcs_to_send} \
      {metadata_to_send}"
