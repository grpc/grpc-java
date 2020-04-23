#!/bin/bash

MISSPELL_ARGS=""
if [[ $1 == "correct" ]]; then
    MISSPELL_ARGS="-w"
fi

bash ./tools/install-misspell.sh &>/dev/null
wait

OUTPUT=$(git ls-files -m | xargs ./bin/misspell "$MISSPELL_ARGS")
rm -rf ./bin

if [[ -n "${OUTPUT// }" && (-z "${MISSPELL_ARGS// }") ]]; then
  echo "ERROR: ${OUTPUT}"
  echo "Please correct them by running bash ./tools/spelling.sh correct"
  exit 1
fi
