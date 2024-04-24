#!/usr/bin/env bash
set -eo pipefail

# Constants
readonly GRPC_LANGUAGE="java"
readonly GITHUB_REPOSITORY_NAME="grpc-java"
readonly TEST_DRIVER_INSTALL_SCRIPT_URL="https://raw.githubusercontent.com/${TEST_DRIVER_REPO_OWNER:-grpc}/psm-interop/${TEST_DRIVER_BRANCH:-main}/.kokoro/psm_interop_kokoro_lib.sh"
readonly BUILD_SCRIPT_DIR="$(dirname "$0")"

echo "Sourcing test driver install script from: ${TEST_DRIVER_INSTALL_SCRIPT_URL}"
source /dev/stdin <<< "$(curl -s "${TEST_DRIVER_INSTALL_SCRIPT_URL}")"
psm::run "lb"
