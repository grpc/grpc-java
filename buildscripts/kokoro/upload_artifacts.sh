#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR="$(cd "$(dirname "$0")"/../.. && pwd)"

echo "Verifying that all artifacts are here..."
find "$KOKORO_GFILE_DIR"

# The output from all the jobs are coalesced into a single dir
LOCAL_MVN_ARTIFACTS="$KOKORO_GFILE_DIR"/github/grpc-java/mvn-artifacts/
LOCAL_OTHER_ARTIFACTS="$KOKORO_GFILE_DIR"/github/grpc-java/artifacts/

# verify that files from all 3 grouped jobs are present.
# platform independent artifacts, from linux job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'grpc-core-*.jar' | wc -l)" != '0' ]]

# android artifact from linux job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'grpc-android-*.aar' | wc -l)" != '0' ]]

# cronet artifact from linux job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'grpc-cronet-*.aar' | wc -l)" != '0' ]]

# binder artifact from linux job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'grpc-binder-*.aar' | wc -l)" != '0' ]]

# from linux job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-linux-x86_64.exe' | wc -l)" != '0' ]]
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-linux-x86_32.exe' | wc -l)" != '0' ]]

# for linux aarch64 platform
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-linux-aarch_64.exe' | wc -l)" != '0' ]]

# for linux ppc64le platform
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-linux-ppcle_64.exe' | wc -l)" != '0' ]]

# for linux s390x platform
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-linux-s390_64.exe' | wc -l)" != '0' ]]

# from macos job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-osx-x86_64.exe' | wc -l)" != '0' ]]
# copy all x86 artifacts to aarch until native artifacts are built
find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-osx-x86_64.exe*' -exec bash -c 'cp "${0}" "${0/x86/aarch}"' {} \;

# from windows job:
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-windows-x86_64.exe' | wc -l)" != '0' ]]
[[ "$(find "$LOCAL_MVN_ARTIFACTS" -type f -iname 'protoc-gen-grpc-java-*-windows-x86_32.exe' | wc -l)" != '0' ]]


mkdir -p ~/.config/
gsutil cp gs://grpc-testing-secrets/sonatype_credentials/sonatype-upload ~/.config/sonatype-upload

mkdir -p ~/java_signing/
gsutil cp -r gs://grpc-testing-secrets/java_signing/ ~/
gpg --batch  --import ~/java_signing/grpc-java-team-sonatype.asc

gsutil cat gs://grpc-testing-secrets/dockerhub_credentials/grpcpackages.password \
  | docker login --username grpcpackages --password-stdin

# gpg commands changed between v1 and v2 are different.
gpg --version

# This is the version found on kokoro.
if gpg --version | grep 'gpg (GnuPG) 1.'; then
  # This command was tested on 1.4.16
  find "$LOCAL_MVN_ARTIFACTS" -type f \
    -not -name "maven-metadata.xml*" -not -name "*.sha*" -not -name "*.md5" -exec \
    bash -c 'cat ~/java_signing/passphrase | gpg --batch --passphrase-fd 0 --detach-sign -a {}' \;
fi

# This is the version found on corp workstations. Maybe kokoro will be updated to gpg2 some day.
if gpg --version | grep 'gpg (GnuPG) 2.'; then
  # This command was tested on 2.2.2
  find "$LOCAL_MVN_ARTIFACTS" -type f \
    -not -name "maven-metadata.xml*" -not -name "*.sha*" -not -name "*.md5" -exec \
    gpg --batch --passphrase-file ~/java_signing/passphrase --pinentry-mode loopback \
    --detach-sign -a {} \;
fi

# Just the numbers; does not include leading 'v'
VERSION="$(cat $LOCAL_OTHER_ARTIFACTS/version)"
EXAMPLE_HOSTNAME_ID="$(cat "$LOCAL_OTHER_ARTIFACTS/example-hostname.id")"
docker load --input "$LOCAL_OTHER_ARTIFACTS/example-hostname.tar"
LATEST_VERSION="$((echo "v$VERSION"; git ls-remote -t --refs https://github.com/grpc/grpc-java.git | cut -f 2 | sed s#refs/tags/##) | sort -V | tail -n 1)"


STAGING_REPO=a93898609ef848
"$GRPC_JAVA_DIR"/buildscripts/sonatype-upload.sh "$STAGING_REPO" "$LOCAL_MVN_ARTIFACTS"

docker tag "$EXAMPLE_HOSTNAME_ID" "grpc/java-example-hostname:${VERSION}"
docker push "grpc/java-example-hostname:${VERSION}"
if [[ "v$VERSION" = "$LATEST_VERSION" ]]; then
  docker tag "$EXAMPLE_HOSTNAME_ID" grpc/java-example-hostname:latest
  docker push grpc/java-example-hostname:latest
fi
