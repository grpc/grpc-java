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

# This file defines psm::lang::build_docker_images, which is directly called
# from psm_interop_kokoro_lib.sh.

# Used locally.
readonly BUILD_APP_PATH="interop-testing/build/install/grpc-interop-testing"

#######################################
# Builds the test app using gradle and smoke-checks its binaries
# Globals:
#   SRC_DIR Absolute path to the source repo.
#   BUILD_APP_PATH
# Arguments:
#   None
# Outputs:
#   Writes the output of xds-test-client and xds-test-server --help to stderr
#######################################
_build_java_test_app() {
  psm::tools::log "Building Java test app"
  cd "${SRC_DIR}"

  set -x
  GRADLE_OPTS="-Dorg.gradle.jvmargs='-Xmx1g'" \
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test \
    -PskipCodegen=true -PskipAndroid=true --console=plain
  set +x

  psm::tools::log "Test-run grpc-java PSM interop binaries"
  psm::tools::run_ignore_exit_code "${SRC_DIR}/${BUILD_APP_PATH}/bin/xds-test-client" --help
  psm::tools::run_ignore_exit_code "${SRC_DIR}/${BUILD_APP_PATH}/bin/xds-test-server" --help
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
psm::lang::build_docker_images() {
  local java_build_log="${BUILD_LOGS_ROOT}/build-lang-java.log"
  _build_java_test_app |& tee "${java_build_log}"

  psm::tools::log "Building Java xDS interop test app Docker images"
  local docker_dir="${SRC_DIR}/buildscripts/xds-k8s"
  local build_dir
  build_dir="$(mktemp -d)"

  # Copy Docker files, log properties, and the test app to the build dir
  {
    cp -v "${docker_dir}/"*.Dockerfile "${build_dir}"
    cp -v "${docker_dir}/"*.properties "${build_dir}"
    cp -rv "${SRC_DIR}/${BUILD_APP_PATH}" "${build_dir}"
  } >> "${java_build_log}"


  # cloudbuild.yaml substitution variables
  local substitutions=""
  substitutions+="_SERVER_IMAGE_NAME=${SERVER_IMAGE_NAME},"
  substitutions+="_CLIENT_IMAGE_NAME=${CLIENT_IMAGE_NAME},"
  substitutions+="COMMIT_SHA=${GIT_COMMIT},"
  substitutions+="BRANCH_NAME=${TESTING_VERSION},"

  # Run Google Cloud Build
  gcloud builds submit "${build_dir}" \
    --config="${docker_dir}/cloudbuild.yaml" \
    --substitutions="${substitutions}" \
    | tee -a "${java_build_log}"
}
