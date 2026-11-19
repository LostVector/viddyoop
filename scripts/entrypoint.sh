#!/bin/sh
set -eu
# Resolve the default here rather than in Docker ENV: an image-level canonical
# default would mask an explicitly supplied legacy address during an upgrade.
if [ -z "${VIDDYOOP_TEMPORAL+x}" ]; then
    export VIDDYOOP_TEMPORAL="${VIDYOOP_TEMPORAL-temporal:7233}"
fi
exec java -jar /app/viddyoop.jar "$@"
