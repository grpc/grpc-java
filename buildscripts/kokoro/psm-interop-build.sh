#!/usr/bin/env bash
# Copyright 2024 gRPC authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -eo pipefail

# Constants
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
  GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1g'" \
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
#   TESTING_VERSION: version branch under test, f.e. v1.42.x, master
# Arguments:
#   None
# Outputs:
#   Writes the output of `gcloud builds submit` to stdout, stderr
#######################################
build_test_app_docker_images() {
  build_java_test_app

  echo "Building Java xDS interop test app Docker images"
  local docker_dir="${SRC_DIR}/buildscripts/xds-k8s"
  local build_dir
  build_dir="$(mktemp -d)"
  # Copy Docker files, log properties, and the test app to the build dir
  cp -v "${docker_dir}/"*.Dockerfile "${build_dir}"
  cp -v "${docker_dir}/"*.properties "${build_dir}"
  cp -rv "${SRC_DIR}/${BUILD_APP_PATH}" "${build_dir}"
  # Pick a branch name for the built image
  local branch_name='experimental'
  if is_version_branch "${TESTING_VERSION}"; then
    branch_name="${TESTING_VERSION}"
  fi
  # Run Google Cloud Build
  gcloud builds submit "${build_dir}" \
    --config "${docker_dir}/cloudbuild.yaml" \
    --substitutions "_SERVER_IMAGE_NAME=${SERVER_IMAGE_NAME},_CLIENT_IMAGE_NAME=${CLIENT_IMAGE_NAME},COMMIT_SHA=${GIT_COMMIT},BRANCH_NAME=${branch_name}"
}
