#!/bin/sh
set -eu
: "${VIDDYOOP_NAMESPACE:=${VIDYOOP_NAMESPACE:-default}}"
: "${VIDDYOOP_HISTORY_RETENTION:=${VIDYOOP_HISTORY_RETENTION:-30d}}"
for attempt in $(seq 1 40); do
    if temporal operator cluster health --address temporal:7233; then
        if temporal operator namespace describe --address temporal:7233 --namespace "$VIDDYOOP_NAMESPACE"; then
            exit 0
        fi
        if temporal operator namespace create --address temporal:7233 --namespace "$VIDDYOOP_NAMESPACE" --retention "$VIDDYOOP_HISTORY_RETENTION"; then
            exit 0
        fi
    fi
    sleep 3
done
echo 'Temporal namespace is not ready' >&2
exit 1
