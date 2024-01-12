#!/bin/bash

spongify_logs() {
  find . -name 'TEST-*.xml' | xargs -I{} -P30 sh -c $'f=\'{}\'; mkdir "${f%.xml}" && cp "$f" "${f%.xml}/sponge_log.xml"'
  # Just to output the time.
  echo "spongify_logs complete"
}
