#!/usr/bin/env bash
set -eo pipefail

# Constants
readonly GITHUB_REPOSITORY_NAME="grpc-java"
# GKE Cluster
readonly GKE_CLUSTER_NAME="interop-test-psm-sec1-us-central1"
readonly GKE_CLUSTER_ZONE="us-central1-a"
## xDS test server/client Docker images
readonly SERVER_IMAGE_NAME="gcr.io/grpc-testing/xds-interop/java-server"
readonly CLIENT_IMAGE_NAME="gcr.io/grpc-testing/xds-interop/java-client"
readonly FORCE_IMAGE_BUILD="${FORCE_IMAGE_BUILD:-0}"
readonly BUILD_APP_PATH="interop-testing/build/install/grpc-interop-testing"

#######################################
# Builds the test app using gradle and smoke-checks its binaries
# Globals:
#   SRC_DIR
#   BUILD_APP_PATH
# Arguments:
#   None
# Outputs:
#   Writes the output of xds-test-client and xds-test-server --help to stderr
#######################################
build_java_test_app() {
  echo "Building Java test app"
  cd "${SRC_DIR}"
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test \
    -PskipCodegen=true -PskipAndroid=true --console=plain

  # Test-run binaries
  run_ignore_exit_code "${SRC_DIR}/${BUILD_APP_PATH}/bin/xds-test-client" --help
  run_ignore_exit_code "${SRC_DIR}/${BUILD_APP_PATH}/bin/xds-test-server" --help
}

#######################################
# Builds test app Docker images and pushes them to GCR
# Globals:
#   BUILD_APP_PATH
#   SERVER_IMAGE_NAME: Test server Docker image name
#   CLIENT_IMAGE_NAME: Test client Docker image name
#   GIT_COMMIT: SHA-1 of git commit being built
# Arguments:
#   None
# Outputs:
#   Writes the output of `gcloud builds submit` to stdout, stderr
#######################################
build_test_app_docker_images() {
  echo "Building Java xDS interop test app Docker images"
  local docker_dir="${SRC_DIR}/buildscripts/xds-k8s"
  local build_dir
  build_dir="$(mktemp -d)"
  # Copy Docker files, log properties, and the test app to the build dir
  cp -v "${docker_dir}/"*.Dockerfile "${build_dir}"
  cp -v "${docker_dir}/"*.properties "${build_dir}"
  cp -rv "${SRC_DIR}/${BUILD_APP_PATH}" "${build_dir}"
  # Run Google Cloud Build
  gcloud builds submit "${build_dir}" \
    --config "${docker_dir}/cloudbuild.yaml" \
    --substitutions "_SERVER_IMAGE_NAME=${SERVER_IMAGE_NAME},_CLIENT_IMAGE_NAME=${CLIENT_IMAGE_NAME},COMMIT_SHA=${GIT_COMMIT}"
  # TODO(sergiitk): extra "cosmetic" tags for versioned branches, e.g. v1.34.x
  # TODO(sergiitk): do this when adding support for custom configs per version
}

#######################################
# Builds test app and its docker images unless they already exist
# Globals:
#   SERVER_IMAGE_NAME: Test server Docker image name
#   CLIENT_IMAGE_NAME: Test client Docker image name
#   GIT_COMMIT: SHA-1 of git commit being built
#   FORCE_IMAGE_BUILD
# Arguments:
#   None
# Outputs:
#   Writes the output to stdout, stderr
#######################################
build_docker_images_if_needed() {
  # Check if images already exist
  server_tags="$(gcloud_gcr_list_image_tags "${SERVER_IMAGE_NAME}" "${GIT_COMMIT}")"
  printf "Server image: %s:%s\n" "${SERVER_IMAGE_NAME}" "${GIT_COMMIT}"
  echo "${server_tags:-Server image not found}"

  client_tags="$(gcloud_gcr_list_image_tags "${CLIENT_IMAGE_NAME}" "${GIT_COMMIT}")"
  printf "Client image: %s:%s\n" "${CLIENT_IMAGE_NAME}" "${GIT_COMMIT}"
  echo "${client_tags:-Client image not found}"

  # Build if any of the images are missing, or FORCE_IMAGE_BUILD=1
  if [[ "${FORCE_IMAGE_BUILD}" == "1" || -z "${server_tags}" || -z "${client_tags}" ]]; then
    build_java_test_app
    build_test_app_docker_images
  else
    echo "Skipping Java test app build"
  fi
}

#######################################
# Executes the test case
# Globals:
#   TEST_DRIVER_FLAGFILE: Relative path to test driver flagfile
#   KUBE_CONTEXT: The name of kubectl context with GKE cluster access
#   TEST_XML_OUTPUT_DIR: Output directory for the test xUnit XML report
#   SERVER_IMAGE_NAME: Test server Docker image name
#   CLIENT_IMAGE_NAME: Test client Docker image name
#   GIT_COMMIT: SHA-1 of git commit being built
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
  set -x
  python -m "tests.${test_name}" \
    --flagfile="${TEST_DRIVER_FLAGFILE}" \
    --kube_context="${KUBE_CONTEXT}" \
    --server_image="${SERVER_IMAGE_NAME}:${GIT_COMMIT}" \
    --client_image="${CLIENT_IMAGE_NAME}:${GIT_COMMIT}" \
    --xml_output_file="${TEST_XML_OUTPUT_DIR}/${test_name}/sponge_log.xml" \
    --force_cleanup
  set +x
}

#######################################
# Main function: provision software necessary to execute tests, and run them
# Globals:
#   KOKORO_ARTIFACTS_DIR
#   GITHUB_REPOSITORY_NAME
#   SRC_DIR: Populated with absolute path to the source repo
#   TEST_DRIVER_REPO_DIR: Populated with the path to the repo containing
#                         the test driver
#   TEST_DRIVER_FULL_DIR: Populated with the path to the test driver source code
#   TEST_DRIVER_FLAGFILE: Populated with relative path to test driver flagfile
#   TEST_XML_OUTPUT_DIR: Populated with the path to test xUnit XML report
#   GIT_ORIGIN_URL: Populated with the origin URL of git repo used for the build
#   GIT_COMMIT: Populated with the SHA-1 of git commit being built
#   GIT_COMMIT_SHORT: Populated with the short SHA-1 of git commit being built
#   KUBE_CONTEXT: Populated with name of kubectl context with GKE cluster access
# Arguments:
#   None
# Outputs:
#   Writes the output of test execution to stdout, stderr
#######################################
main() {
  local script_dir
  script_dir="$(dirname "$0")"
  # shellcheck source=buildscripts/kokoro/xds-k8s-install-test-driver.sh
  source "${script_dir}/xds-k8s-install-test-driver.sh"
  set -x
  if [[ -n "${KOKORO_ARTIFACTS_DIR}" ]]; then
    kokoro_setup_test_driver "${GITHUB_REPOSITORY_NAME}"
  else
    local_setup_test_driver "${script_dir}"
  fi
  build_docker_images_if_needed
  # Run tests
  cd "${TEST_DRIVER_FULL_DIR}"
  run_test baseline_test
  run_test security_test
}

main "$@"
