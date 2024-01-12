#!/bin/bash

spongify_logs() {
  find . -name 'TEST-*.xml' | xargs -I{} -P30 sh -c $'f=\'{}\'; mkdir "${f%.xml}" && cp "$f" "${f%.xml}/sponge_log.xml"'
}
