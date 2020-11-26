#!/usr/bin/env bash
set -eo pipefail

# Constants
readonly GITHUB_REPOSITORY_NAME="grpc-java"
readonly SCRIPT_DIR=$(dirname "$0")
# GKE Cluster
readonly GKE_CLUSTER_NAME="interop-test-psm-sec1-us-central1"
readonly GKE_CLUSTER_ZONE="us-central1-a"
## xDS test server/client Docker images
readonly IMAGE_NAME="gcr.io/grpc-testing/xds-k8s-test-java"
readonly IMAGE_BUILD_SKIP="${IMAGE_BUILD_SKIP:-1}"

build_java_test_app() {
  echo "Building Java test app"
  cd "${SRC_DIR}"
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
  # Test-run binaries
  run_safe "${TEST_APP_BUILD_APP_DIR}/bin/xds-test-client" --help
  run_safe "${TEST_APP_BUILD_APP_DIR}/bin/xds-test-server" --help
}

build_test_app_docker_images() {
  local docker_dir="${SRC_DIR}/buildscripts/xds-k8s"
  echo "Building Java test app Docker Images"
  # Copy Docker files and log properties to the build dir
  cp -rv "${docker_dir}/"*.Dockerfile "${TEST_APP_BUILD_INSTALL_DIR}"
  cp -rv "${docker_dir}/"*.properties "${TEST_APP_BUILD_INSTALL_DIR}"
  # Run Google Cloud Build
  gcloud builds submit "${TEST_APP_BUILD_INSTALL_DIR}" \
    --config "${docker_dir}/cloudbuild.yaml" \
    --substitutions "_IMAGE_NAME=${IMAGE_NAME},_SERVER_IMAGE_TAG=${SERVER_IMAGE_TAG},_CLIENT_IMAGE_TAG=${CLIENT_IMAGE_TAG}"
  # TODO(sergiitk): extra "cosmetic" tags for versioned branches
}

build_docker_images_if_needed() {
  # Check if images already exist
  server_tags=$(gcloud_gcr_list_matching_tags "${IMAGE_NAME}" "${SERVER_IMAGE_TAG}")
  printf "Server image: %s:%s\n%s\n" "${IMAGE_NAME}" "${SERVER_IMAGE_TAG}" "${server_tags:-Server image not found}"
  client_tags=$(gcloud_gcr_list_matching_tags "${IMAGE_NAME}" "${CLIENT_IMAGE_TAG}")
  printf "Client image: %s:%s\n%s\n" "${IMAGE_NAME}" "${CLIENT_IMAGE_TAG}" "${client_tags:-Client image not found}"

  # Build if one of images is missing or IMAGE_BUILD_SKIP=0
  if [[ "${IMAGE_BUILD_SKIP}" == "0" || -z "${server_tags}" && -z "${client_tags}" ]]; then
    readonly TEST_APP_BUILD_INSTALL_DIR="${SRC_DIR}/interop-testing/build/install"
    readonly TEST_APP_BUILD_APP_DIR="${TEST_APP_BUILD_OUT_DIR}/grpc-interop-testing"
    build_java_test_app
    build_test_app_docker_images
  else
    echo "Skipping Java test app build"
  fi
}

run_test() {
  # Test driver usage:
  # https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#basic-usage
  local test_name="${1:?Usage: run_test test_name}"
  cd "${TEST_DRIVER_FULL_DIR}"
  set -x
  python -m "tests.${test_name}" \
    --flagfile="${TEST_DRIVER_CONFIG}" \
    --kube_context="${KUBE_CONTEXT}" \
    --server_image="${IMAGE_NAME}:${SERVER_IMAGE_TAG}" \
    --client_image="${IMAGE_NAME}:${CLIENT_IMAGE_TAG}" \
    --xml_output_file="${TEST_XML_OUTPUT_DIR}/${test_name}/sponge_log.xml" \
    --force_cleanup
  set +x
}

main() {
  # Intentional: source from the same dir as xds-k8s.sh
  # shellcheck disable=SC1090
  source "${SCRIPT_DIR}/xds-k8s-install-test-driver.sh"
  set -x
  if [[ -n "${KOKORO_ARTIFACTS_DIR}" ]]; then
    kokoro_init "${GITHUB_REPOSITORY_NAME}"
  else
    local_init "${SCRIPT_DIR}"
  fi
  readonly SERVER_IMAGE_TAG="server-${GIT_COMMIT_SHORT}"
  readonly CLIENT_IMAGE_TAG="client-${GIT_COMMIT_SHORT}"
  build_docker_images_if_needed
  # Run tests
  run_test baseline_test
  run_test security_test
}

main "$@"
