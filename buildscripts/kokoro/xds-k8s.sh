#!/usr/bin/env bash
set -eo pipefail

# Constants
readonly GITHUB_REPOSITORY_NAME="grpc-java"
# GKE Cluster
readonly GKE_CLUSTER_NAME="interop-test-psm-sec1-us-central1"
readonly GKE_CLUSTER_ZONE="us-central1-a"
## xDS test server/client Docker images
readonly IMAGE_NAME="gcr.io/grpc-testing/xds-k8s-test-java"
readonly IMAGE_BUILD_SKIP="${IMAGE_BUILD_SKIP:-1}"
# Build paths
readonly BUILD_INSTALL_PATH="interop-testing/build/install"
readonly BUILD_APP_PATH="${BUILD_INSTALL_PATH}/grpc-interop-testing"

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
#   BUILD_INSTALL_PATH
#   IMAGE_NAME
#   SERVER_IMAGE_TAG
#   CLIENT_IMAGE_TAG
# Arguments:
#   None
# Outputs:
#   Writes the output of `gcloud builds submit` to stdout, stderr
#######################################
build_test_app_docker_images() {
  local docker_dir="${SRC_DIR}/buildscripts/xds-k8s"
  echo "Building Java test app Docker Images"
  # Copy Docker files and log properties to the build dir
  cp -rv "${docker_dir}/"*.Dockerfile "${SRC_DIR}/${BUILD_INSTALL_PATH}"
  cp -rv "${docker_dir}/"*.properties "${SRC_DIR}/${BUILD_INSTALL_PATH}"
  # TODO(sergiitk): extra "cosmetic" tags for versioned branches
  # Run Google Cloud Build
  gcloud builds submit "${SRC_DIR}/${BUILD_INSTALL_PATH}" \
    --config "${docker_dir}/cloudbuild.yaml" \
    --substitutions "_IMAGE_NAME=${IMAGE_NAME},_SERVER_IMAGE_TAG=${SERVER_IMAGE_TAG},_CLIENT_IMAGE_TAG=${CLIENT_IMAGE_TAG}"
}

#######################################
# Builds test app and its docker images unless they already exist
# Globals:
#   IMAGE_NAME
#   SERVER_IMAGE_TAG
#   CLIENT_IMAGE_TAG
#   IMAGE_BUILD_SKIP
# Arguments:
#   None
# Outputs:
#   Writes the output to stdout, stderr
#######################################
build_docker_images_if_needed() {
  # Check if images already exist
  server_tags="$(gcloud_gcr_list_image_tags "${IMAGE_NAME}" "${SERVER_IMAGE_TAG}")"
  printf "Server image: %s:%s\n" "${IMAGE_NAME}" "${SERVER_IMAGE_TAG}"
  echo "${server_tags:-Server image not found}"

  client_tags="$(gcloud_gcr_list_image_tags "${IMAGE_NAME}" "${CLIENT_IMAGE_TAG}")"
  printf "Client image: %s:%s\n" "${IMAGE_NAME}" "${CLIENT_IMAGE_TAG}"
  echo "${client_tags:-Client image not found}"

  # Build if one of images is missing or IMAGE_BUILD_SKIP=0
  if [[ "${IMAGE_BUILD_SKIP}" == "0" || -z "${server_tags}" && -z "${client_tags}" ]]; then
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
#   IMAGE_NAME: The name of GCR image with the test app
#   SERVER_IMAGE_TAG: Test server image tag used in this build
#   CLIENT_IMAGE_TAG: Test client image tag used in this build
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
    --server_image="${IMAGE_NAME}:${SERVER_IMAGE_TAG}" \
    --client_image="${IMAGE_NAME}:${CLIENT_IMAGE_TAG}" \
    --xml_output_file="${TEST_XML_OUTPUT_DIR}/${test_name}/sponge_log.xml" \
    --force_cleanup
  set +x
}

#######################################
# Main function: provision software necessary to execute tests, and run them
# Globals:
#   KOKORO_ARTIFACTS_DIR
#   GITHUB_REPOSITORY_NAME
#   SERVER_IMAGE_TAG: Populated with test server image tag used in this build
#   CLIENT_IMAGE_TAG: Populated with test client image tag used in this build
#   SRC_DIR: Populated with absolute path to the source repo
#   TEST_DRIVER_REPO_DIR: Populated with the path to the repo containing
#                         the test driver
#   TEST_DRIVER_FULL_DIR: Populated with the path to the test driver source code
#   TEST_DRIVER_FLAGFILE: Populated with relative path to test driver flagfile
#   TEST_XML_OUTPUT_DIR: Populated with the path to test xUnit XML report
#   GIT_ORIGIN_URL: Populated with the origin URL of git repo used for the build
#   GIT_COMMIT_SHORT: Populated with the short SHA-1 of git commit being built
#   KUBE_CONTEXT: Populated with name of kubectl context with GKE cluster access
#   PRIVATE_API_KEY: Exported. Populated with name GCP project secret key.
#                    Used by the test driver to access private APIs
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
  readonly SERVER_IMAGE_TAG="server-${GIT_COMMIT_SHORT}"
  readonly CLIENT_IMAGE_TAG="client-${GIT_COMMIT_SHORT}"
  build_docker_images_if_needed
  # Run tests
  cd "${TEST_DRIVER_FULL_DIR}"
  run_test baseline_test
  run_test security_test
}

main "$@"
