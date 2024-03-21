#!/bin/bash

spongify_logs() {
  local f
  while read -r f; do
    mkdir "${f%.xml}"
    cp "$f" "${f%.xml}/sponge_log.xml"
  done < <(find "${KOKORO_ARTIFACTS_DIR:-.}" -name 'TEST-*.xml')
  # Just to output the time.
  echo "spongify_logs complete"
}
