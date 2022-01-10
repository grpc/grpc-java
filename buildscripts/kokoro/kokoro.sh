#!/bin/bash

spongify_logs() {
  local f
  for f in $(find "${KOKORO_ARTIFACTS_DIR:-.}" -name 'TEST-*.xml'); do
    mkdir "${f%.xml}"
    cp "$f" "${f%.xml}/sponge_log.xml"
  done
}
