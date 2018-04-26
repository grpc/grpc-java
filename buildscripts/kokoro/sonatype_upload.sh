#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR=$(cd $(dirname $0)/../.. && pwd)

# A place holder at the moment

echo "all the artifacts should be here..."
find $KOKORO_GFILE_DIR

mkdir -p ~/.config/
gsutil cp gs://grpc-testing-secrets/sonatype_credentials/sonatype-upload ~/.config/sonatype-upload

mkdir -p ~/java_signing/
gsutil cp -r gs://grpc-testing-secrets/java_signing/ ~/
gpg --batch  --import ~/java_signing/grpc-java-team-sonatype.asc

set +x
find ~/java_signing -type f -exec \
  gpg --batch --passphrase-file ~/java_signing/passphrase --detach-sign -o {}.asc {} \;
set -x

STAGING_REPO=a93898609ef848
find $KOKORO_GFILE_DIR -name 'mvn-artifacts' -type d -exec \
  $GRPC_JAVA_DIR/buildcripts/sonatype-upload-util.sh \
  $STAGING_REPO {} \;
