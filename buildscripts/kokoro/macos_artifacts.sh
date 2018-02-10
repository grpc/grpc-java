#!/bin/bash

# This script assumes `set -e`. Removing it may lead to undefined behavior.
set -exu -o pipefail

BUILD_MVN_ARTIFACTS='true' bash "$(dirname $0)/unix.sh"
