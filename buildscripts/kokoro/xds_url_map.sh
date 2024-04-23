#!/usr/bin/env bash
set -eo pipefail

# Constants
readonly GRPC_LANGUAGE="java"
readonly GITHUB_REPOSITORY_NAME="grpc-java"
readonly TEST_DRIVER_INSTALL_SCRIPT_URL="https://raw.githubusercontent.com/${TEST_DRIVER_REPO_OWNER:-grpc}/psm-interop/${TEST_DRIVER_BRANCH:-main}/.kokoro/psm_interop_kokoro_lib.sh"
readonly BUILD_SCRIPT_DIR="$(dirname "$0")"

#######################################
# Executes the test case
# Globals:
#   TEST_DRIVER_FLAGFILE: Relative path to test driver flagfile
#   KUBE_CONTEXT: The name of kubectl context with GKE cluster access
#   TEST_XML_OUTPUT_DIR: Output directory for the test xUnit XML report
#   CLIENT_IMAGE_NAME: Test client Docker image name
#   GIT_COMMIT: SHA-1 of git commit being built
#   TESTING_VERSION: version branch under test: used by the framework to
#                     determine the supported PSM features.
# Arguments:
#   Test case name
# Outputs:
#   Writes the output of test execution to stdout, stderr
#   Test xUnit report to ${TEST_XML_OUTPUT_DIR}/${test_name}/sponge_log.xml
#######################################
run_test() {
  # Test driver usage:
  # https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#basic-usage
  local test_name="${1:?Usage: run_test test_name}"
  local out_dir="${TEST_XML_OUTPUT_DIR}/${test_name}"
  mkdir -pv "${out_dir}"
  set -x
  python -m "tests.${test_name}" \
    --flagfile="${TEST_DRIVER_FLAGFILE}" \
    --flagfile="config/url-map.cfg" \
    --kube_context="${KUBE_CONTEXT}" \
    --client_image="${CLIENT_IMAGE_NAME}:${GIT_COMMIT}" \
    --testing_version="${TESTING_VERSION}" \
    --collect_app_logs \
    --log_dir="${out_dir}" \
    --xml_output_file="${out_dir}/sponge_log.xml" \
    |& tee "${out_dir}/sponge_log.log"
}

#######################################
# Main function: provision software necessary to execute tests, and run them
# Globals:
#   TEST_DRIVER_INSTALL_SCRIPT_URL
# Arguments:
#   None
# Outputs:
#   Writes the output of test execution to stdout, stderr
#######################################
main() {
  echo "Sourcing test driver install script from: ${TEST_DRIVER_INSTALL_SCRIPT_URL}"
  source /dev/stdin <<< "$(curl -s "${TEST_DRIVER_INSTALL_SCRIPT_URL}")"
  psm::run "url_map"
}

main "$@"
