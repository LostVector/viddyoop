#!/bin/sh
set -eu
for attempt in $(seq 1 40); do
    if temporal operator cluster health --address temporal:7233; then
        if temporal operator namespace describe --address temporal:7233 --namespace "$VIDYOOP_NAMESPACE"; then
            exit 0
        fi
        if temporal operator namespace create --address temporal:7233 --namespace "$VIDYOOP_NAMESPACE" --retention "$VIDYOOP_HISTORY_RETENTION"; then
            exit 0
        fi
    fi
    sleep 3
done
echo 'Temporal namespace is not ready' >&2
exit 1
