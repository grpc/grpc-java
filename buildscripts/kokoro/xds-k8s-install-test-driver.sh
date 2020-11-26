#!/usr/bin/env bash
# TODO(sergiitk): move to grpc/grpc when implementing support of other languages
set -eo pipefail

# Constants
readonly PYTHON_BIN="python3.6"
# Test driver
readonly TEST_DRIVER_REPO_NAME="grpc"
readonly TEST_DRIVER_REPO_URL="https://github.com/grpc/grpc.git"
readonly TEST_DRIVER_BRANCH="${TEST_DRIVER_BRANCH:-master}"
readonly TEST_DRIVER_PATH="tools/run_tests/xds_k8s_test_driver"
readonly TEST_DRIVER_PROTOS_PATH="src/proto/grpc/testing"

run_safe() {
  # Run command end report its exit code.
  # Don't terminate the script if the code is negative.
  local exit_code=-1
  "$@" || exit_code=$?
  echo "Exit code: ${exit_code}"
}

parse_src_repo_git_info() {
  local src_dir="${SRC_DIR:?SRC_DIR must be set}"
  readonly GIT_ORIGIN_URL=$(git -C "${src_dir}" remote get-url origin)
  readonly GIT_COMMIT_SHORT=$(git -C "${src_dir}" rev-parse --short HEAD)
}

gcloud_gcr_list_matching_tags() {
  gcloud container images list-tags --format="table[box](tags,digest,timestamp.date())" --filter="tags:$2" "$1"
}

gcloud_update() {
  echo "Update gcloud components:"
  gcloud -q components update
}

gcloud_get_cluster_credentials() {
  gcloud container clusters get-credentials "${GKE_CLUSTER_NAME}" --zone "${GKE_CLUSTER_ZONE}"
  readonly KUBE_CONTEXT="$(kubectl config current-context)"
}

test_driver_get_source() {
  if [[ -d "${TEST_DRIVER_REPO_DIR}" ]]; then
    echo "Found driver directory: ${TEST_DRIVER_REPO_DIR}"
  else
    echo "Cloning driver to ${TEST_DRIVER_REPO_URL} branch ${TEST_DRIVER_BRANCH} to ${TEST_DRIVER_REPO_DIR}"
    git clone -b "${TEST_DRIVER_BRANCH}" --depth=1 "${TEST_DRIVER_REPO_URL}" "${TEST_DRIVER_REPO_DIR}"
  fi
}

test_driver_pip_install() {
  echo "Install python dependencies"
  cd "${TEST_DRIVER_FULL_DIR}"

  # Create and activate virtual environment already using one
  if [[ -z "${VIRTUAL_ENV}" ]]; then
    local venv_dir="${TEST_DRIVER_FULL_DIR}/venv"
    if [[ -d "${venv_dir}" ]]; then
      echo "Found python virtual environment directory: ${venv_dir}"
    else
      echo "Creating python virtual environment: ${venv_dir}"
      "${PYTHON_BIN} -m venv ${venv_dir}"
    fi
    source "${venv_dir}/bin/activate"
  fi

  pip install -r requirements.txt
  echo "Installed Python packages:"
  pip list
}

test_driver_compile_protos() {
  declare -a protos
  protos=(
    "${TEST_DRIVER_PROTOS_PATH}/test.proto"
    "${TEST_DRIVER_PROTOS_PATH}/messages.proto"
    "${TEST_DRIVER_PROTOS_PATH}/empty.proto"
  )
  echo "Generate python code from grpc.testing protos: ${protos[*]}"
  cd "${TEST_DRIVER_REPO_DIR}"
  python3 -m grpc_tools.protoc \
    --proto_path=. \
    --python_out="${TEST_DRIVER_FULL_DIR}" \
    --grpc_python_out="${TEST_DRIVER_FULL_DIR}" \
    "${protos[@]}"
  local protos_out_dir="${TEST_DRIVER_FULL_DIR}/${TEST_DRIVER_PROTOS_PATH}"
  echo "Generated files ${protos_out_dir}:"
  ls -Fl "${protos_out_dir}"
}

test_driver_install() {
  readonly TEST_DRIVER_REPO_DIR="${1:?Usage kokoro_init TEST_DRIVER_REPO_DIR}"
  readonly TEST_DRIVER_FULL_DIR="${TEST_DRIVER_REPO_DIR}/${TEST_DRIVER_PATH}"
  # Test driver installation:
  # https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#installation
  test_driver_get_source
  test_driver_pip_install
  test_driver_compile_protos
}

kokoro_print_version() {
  echo "Kokoro VM version:"
  if [[ -f /VERSION ]]; then
    cat /VERSION
  fi
  run_safe lsb_release -a
}

kokoro_write_sponge_properties() {
  # CSV format: "property_name","property_value"
  # Bump TESTS_FORMAT_VERSION when reported test name changed enough to when it
  # makes more sense to discard previous test results from a testgrid board.
  # Use GIT_ORIGIN_URL to exclude test runs executed against repo forks from
  # testgrid reports.
  cat >"${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv" <<EOF
TESTS_FORMAT_VERSION,2
TESTGRID_EXCLUDE,0
GIT_ORIGIN_URL,${GIT_ORIGIN_URL:?GIT_ORIGIN_URL must be set}
GIT_COMMIT_SHORT,${GIT_COMMIT_SHORT:?GIT_COMMIT_SHORT must be set}
EOF
  echo "Sponge properties:"
  cat "${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv"
}

kokoro_export_secrets() {
  readonly PRIVATE_API_KEY=$(cat "${KOKORO_KEYSTORE_DIR}/73836_grpc_xds_interop_tests_gcp_alpha_apis_key")
  export PRIVATE_API_KEY
}

kokoro_setup_python_virtual_environment() {
  # Kokoro provides pyenv, so use it instead `python -m venv`
  echo "Setup pyenv environment"
  eval "$(pyenv init -)"
  eval "$(pyenv virtualenv-init -)"
  echo "Activating python virtual environment"
  pyenv virtualenv 3.6.1 k8s_xds_test_runner
  pyenv local k8s_xds_test_runner
  pyenv activate k8s_xds_test_runner
}

kokoro_init() {
  local src_repository_name="${1:?Usage kokoro_init GITHUB_REPOSITORY_NAME}"
  # Capture Kokoro VM version info in the log.
  kokoro_print_version

  # Kokoro clones repo to ${KOKORO_ARTIFACTS_DIR}/github/${GITHUB_REPOSITORY}
  local github_root="${KOKORO_ARTIFACTS_DIR}/github"
  readonly SRC_DIR="${github_root}/${src_repository_name}"
  local test_driver_repo_dir="${github_root}/${TEST_DRIVER_REPO_NAME}"
  parse_src_repo_git_info SRC_DIR
  # Report extra information about the job via sponge properties
  kokoro_write_sponge_properties

  # Turn off command trace print before exporting secrets.
  local debug_on=0
  if [[ $- =~ x ]]; then
    debug_on=1
    set +x
  fi
  kokoro_export_secrets
  kokoro_setup_python_virtual_environment
  # Re-enable debug output after secrets exported and noisy pyenv activated
  ((debug_on == 0)) || set -x

  # gcloud requires python, so this should be executed after pyenv setup
  gcloud_update
  gcloud_get_cluster_credentials
  test_driver_install "${test_driver_repo_dir}"
  readonly TEST_DRIVER_CONFIG="config/grpc-testing.cfg"
  # Test artifacts dir: xml reports, logs, etc.
  local artifacts_dir="${KOKORO_ARTIFACTS_DIR}/artifacts"
  # Folders after $ARTIFACTS_DIR reported as target name
  readonly TEST_XML_OUTPUT_DIR="${artifacts_dir}/${KOKORO_JOB_NAME}"
  mkdir -p "${artifacts_dir}" "${TEST_XML_OUTPUT_DIR}"
}

local_init() {
  local script_dir="${1:?Usage: local_init SCRIPT_DIR}"
  readonly SRC_DIR="$(git -C "${script_dir}" rev-parse --show-toplevel)"
  parse_src_repo_git_info SRC_DIR
  readonly KUBE_CONTEXT="${KUBE_CONTEXT:-$(kubectl config current-context)}"
  local test_driver_repo_dir
  test_driver_repo_dir="${TEST_DRIVER_REPO_DIR:-$(mktemp -d)/${TEST_DRIVER_REPO_NAME}}"
  test_driver_install "${test_driver_repo_dir}"
  readonly TEST_DRIVER_CONFIG="config/local-dev.cfg"
  # Test out
  readonly TEST_XML_OUTPUT_DIR="${TEST_DRIVER_FULL_DIR}/out"
  mkdir -p "${TEST_XML_OUTPUT_DIR}"
}
