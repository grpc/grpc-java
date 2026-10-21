#!/usr/bin/env bash
set -eo pipefail

# Input parameters to psm:: methods of the install script.
readonly GRPC_LANGUAGE="java"
readonly BUILD_SCRIPT_DIR="$(dirname "$0")"

# Used locally.
readonly TEST_DRIVER_INSTALL_SCRIPT_URL="https://raw.githubusercontent.com/${TEST_DRIVER_REPO_OWNER:-grpc}/psm-interop/${TEST_DRIVER_BRANCH:-main}/.kokoro/psm_interop_kokoro_lib.sh"

psm::lang::source_install_lib() {
  echo "Sourcing test driver install script from: ${TEST_DRIVER_INSTALL_SCRIPT_URL}"
  local install_lib
  # Download to a tmp file.
  install_lib="$(mktemp -d)/psm_interop_kokoro_lib.sh"
  curl -s --retry-connrefused --retry 5 -o "${install_lib}" "${TEST_DRIVER_INSTALL_SCRIPT_URL}"
  # Checksum.
  if command -v sha256sum &> /dev/null; then
    echo "Install script checksum:"
    sha256sum "${install_lib}"
  fi
  source "${install_lib}"
}

psm::lang::source_install_lib
source "${BUILD_SCRIPT_DIR}/psm-interop-build-${GRPC_LANGUAGE}.sh"
psm::run "${PSM_TEST_SUITE}"
