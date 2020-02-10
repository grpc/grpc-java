#!/bin/bash

set -exu -o pipefail
if [[ -f /VERSION ]]; then
  cat /VERSION
fi

sudo apt-get install -y python3-pip
sudo python3 -m pip install grpcio grpcio-tools google-api-python-client google-auth-httplib2

cd github

pushd grpc-java/interop-testing
../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
popd

git clone https://github.com/ericgribkoff/grpc.git

grpc/tools/run_tests/helper_scripts/prep_xds.sh
python3 grpc/tools/run_tests/run_xds_tests.py \
    --test_case=all \
    --project_id=grpc-testing \
    --gcp_suffix=$(date '+%s') \
    --verbose \
    --client_cmd='grpc-java/interop-testing/build/install/grpc-interop-testing/bin/xds-test-client --server=xds-experimental:///{service_host}:{service_port} --stats_port={stats_port} --qps={qps}'
