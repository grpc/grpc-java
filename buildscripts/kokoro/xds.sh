#!/bin/bash

set -exu -o pipefail
if [[ -f /VERSION ]]; then
  cat /VERSION
fi

cd github

pushd grpc-java/interop-testing
branch=$(git branch --all --no-color --contains "${KOKORO_GITHUB_COMMIT}" \
      | grep -v HEAD | head -1)
shopt -s extglob
branch="${branch//[[:space:]]}"
branch="${branch##remotes/origin/}"
shopt -u extglob
../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
popd

git clone -b "${branch}" --single-branch --depth=1 https://github.com/grpc/grpc.git

grpc/tools/run_tests/helper_scripts/prep_xds.sh
JAVA_OPTS=-Djava.util.logging.config.file=grpc-java/buildscripts/xds_logging.properties \
  python3 grpc/tools/run_tests/run_xds_tests.py \
    --test_case=all \
    --project_id=grpc-testing \
    --gcp_suffix=$(date '+%s') \
    --verbose \
    --client_cmd="grpc-java/interop-testing/build/install/grpc-interop-testing/bin/xds-test-client \
      --server=xds-experimental:///{server_uri} \
      --stats_port={stats_port} \
      --qps={qps}"
