#!/bin/bash

MISSPELL_ARGS=""
if [[ $1 == "correct" ]]; then
    MISSPELL_ARGS="-w"
fi

sh ./install-misspell.sh &>/dev/null
wait

OUTPUT=$(git ls-files | xargs ./bin/misspell "$MISSPELL_ARGS")
rm -rf ./bin

if [[ ! -z "${OUTPUT// }" ]]; then
  echo "ERROR: ${OUTPUT}"
  echo "Please correct them by running ./tools/spelling.sh correct"
  exit 1
fi
