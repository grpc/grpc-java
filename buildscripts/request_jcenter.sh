#!/bin/bash

[[ -z "$MAJOR" ]] && { echo "env variable MAJOR must be provided" ; exit 1; }
[[ -z "$MINOR" ]] && { echo "env variable MINOR must be provided" ; exit 1; }
[[ -z "$PATCH" ]] && { echo "env variable PATCH must be provided" ; exit 1; }

VERSION="$MAJOR.$MINOR.$PATCH"

# NOTE: prefix grpc is omitted
ARTIFACTS=(api core context stub auth okhttp protobuf protobuf-lite netty netty-shaded grpclb testing testing-proto interop-testing all alts benchmarks services)
POMS=(bom)

echo "Ping jcenter to cache grpc v${VERSION}"
TEMP_DIR=/tmp/artifacts-${MAJOR}-${MINOR}-${PATCH}
mkdir -p TEMP_DIR

for ARTIFACT in "${ARTIFACTS[@]}"
do
  echo "downloading grpc-$ARTIFACT"
  wget -q --show-progress "https://jcenter.bintray.com/io/grpc/grpc-${ARTIFACT}/${VERSION}/grpc-${ARTIFACT}-${VERSION}.jar" -P $TEMP_DIR
done

for POM in "${POMS[@]}"
do
  echo "downloading grpc-$POM"
  wget -q "https://jcenter.bintray.com/io/grpc/grpc-${POM}/${VERSION}/grpc-${POM}-${VERSION}.pom" -P $TEMP_DIR
done

echo -e "\ndownloaded files..."
ls -al $TEMP_DIR

echo -e '\ndeleting temp dir'
rm -rf $TEMP_DIR

echo "done!"
