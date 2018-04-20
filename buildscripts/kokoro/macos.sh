#!/bin/bash
set -exu -o pipefail

# run unix.sh with default ARCH, which is 64 bit.
$(dirname $0)/unix.sh
