#!/bin/bash
set -exu -o pipefail

# first we need to install the prerequisites required for s390x cross compilation
apt-get update && apt-get install -y g++-s390x-linux-gnu

# now kick off the build for the mvn artifacts for s390x
# mvn artifacts are stored in grpc-java/mvn-artifacts/
SKIP_TESTS=true ARCH=s390_64 "$(dirname $0)"/kokoro/unix.sh

