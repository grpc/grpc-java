#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR=$(cd $(dirname $0)/../.. && pwd)

# A place holder at the moment

echo "all the artifacts should be here..."
find $KOKORO_GFILE_DIR

mkdir ~/.config/
gsutil cp gs://grpc-testing-secrets/sonatype_credentials/sonatype-upload ~/.config/sonatype-upload

STAGING_REPO=a93898609ef848
find $KOKORO_GFILE_DIR -name 'mvn-artifacts' -exec \
  $GRPC_JAVA_DIR/buildcripts/sonatype_upload.sh $STAGING_REPO {} \;
