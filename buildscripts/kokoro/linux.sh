#!/bin/bash
set -exu -o pipefail

# Runs all the tests and builds mvn artifacts.
# mvn artifacts are stored in grpc-java/mvn-artifacts/

ARCH=32 $(dirname $0)/unix.sh
ARCH=64 $(dirname $0)/unix.sh
