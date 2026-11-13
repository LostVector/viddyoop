#!/usr/bin/env bash
# Run inside an isolated Docker VM; force-stop that VM between prepare and verify.
set -euo pipefail
cd "$(dirname "$0")/.."
phase="${1:?prepare, verify, or cleanup}"
trial_root="${2:?absolute path to a new directory on a disposable shared mount}"
case "$trial_root" in /*) ;; *) echo 'An absolute fixture path is required' >&2; exit 2;; esac
export VIDYOOP_IMAGE="${3:-viddyoop:test}"
verification="${4:-viddyoop:verification}"
project="viddyoop-powerloss-$(printf '%s' "$trial_root" | sha256sum | cut -c1-12)"
export VIDYOOP_INBOX_DIR="$trial_root/inbox" VIDYOOP_LIBRARY_DIR="$trial_root/library" VIDYOOP_ARCHIVE_DIR="$trial_root/archive"
export VIDYOOP_POSTGRES_PASSWORD_FILE="$trial_root/postgres-password"
export VIDYOOP_NAMESPACE=default VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_MIN_FREE_BYTES=0
export VIDYOOP_SETTLE_MS=1000 VIDYOOP_POLL_MS=100 VIDYOOP_ARCHIVE_POLICY=none
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$PWD/docker-compose.server.yml" -f "$trial_root/override.yml" -p "$project" "$@"; }
check_catalog() {
  docker run --rm --network none --read-only --tmpfs /tmp:exec,size=67108864,mode=1777 \
    --volumes-from "$(compose ps -q pipeline):ro" --mount "type=bind,src=$PWD/scripts/recovery,dst=/checks,readonly" \
    --env JAVA_TOOL_OPTIONS=-Xmx128m --entrypoint java "$verification" --class-path /app/viddyoop.jar "$@"
}
case "$phase" in
  prepare)
    mkdir "$trial_root" # Refuse to overwrite an existing trial.
    chmod 0755 "$trial_root"
    mkdir "$trial_root/inbox" "$trial_root/library" "$trial_root/archive" "$trial_root/tools"
    chmod 0777 "$trial_root/inbox" "$trial_root/library" "$trial_root/archive"
    python3 -c 'import secrets; print(secrets.token_hex(32))' > "$VIDYOOP_POSTGRES_PASSWORD_FILE"
    chmod 0444 "$VIDYOOP_POSTGRES_PASSWORD_FILE"
    cat > "$trial_root/override.yml" <<'YAML'
services:
  pipeline:
    environment:
      VIDYOOP_HANDBRAKE: /work/boot-gated-handbrake
YAML
    touch "$trial_root/.viddyoop-powerloss-trial"
    cat > "$trial_root/tools/boot-gated-handbrake" <<'SH'
#!/bin/sh
case " $* " in
  *" --output "*)
    if [ "$(cat /work/trial-boot)" = "$(cat /proc/sys/kernel/random/boot_id)" ]; then
      /usr/bin/HandBrakeCLI "$@" --encoder-preset placebo > /work/powerloss-progress 2>&1 &
      child=$!
      while kill -0 "$child" 2>/dev/null; do
        if grep -q 'Encoding:' /work/powerloss-progress; then
          kill -STOP "$child" || exit 99
          echo "$child" > /work/powerloss-boundary
          wait "$child"; exit $?
        fi
        sleep 0.01
      done
      wait "$child"; exit $?
    fi
    ;;
esac
exec /usr/bin/HandBrakeCLI "$@"
SH
    chmod 0755 "$trial_root/tools/boot-gated-handbrake"
    test "$(docker run --rm --network none --entrypoint sha256sum "$VIDYOOP_IMAGE" /app/viddyoop.jar)" = \
         "$(docker run --rm --network none --entrypoint sha256sum "$verification" /app/viddyoop.jar)"
    # Flush harness configuration before accepting any input; no post-start sync.
    compose run --rm --no-deps --entrypoint sh --volume "$trial_root/tools:/tools:ro" pipeline -ec \
      'cp /tools/boot-gated-handbrake /work/boot-gated-handbrake; cat /proc/sys/kernel/random/boot_id > /work/trial-boot; sync /work/boot-gated-handbrake /work/trial-boot /work'
    cat /proc/sys/kernel/random/boot_id > "$trial_root/boot-before"
    docker run --rm --network none --mount "type=bind,src=$trial_root/inbox,dst=/inbox" --entrypoint ffmpeg "$VIDYOOP_IMAGE" \
      -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
      -t 2 -map 0:v -map 1:a -c:v mpeg4 -c:a ac3 /inbox/movie.mkv
    compose up -d --no-build --wait --wait-timeout 120
    boundary=false
    for attempt in $(seq 1 60); do
      if compose exec -T pipeline test -f /work/powerloss-boundary; then boundary=true; break; fi
      sleep 1
    done
    test "$boundary" = true
    compose exec -T pipeline sh -c 'grep "^State:" "/proc/$(cat /work/powerloss-boundary)/status"' > "$trial_root/encoder-before.txt"
    grep -Eq '^State:[[:space:]]+T' "$trial_root/encoder-before.txt"
    compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$trial_root/before.json"
    id="$(python3 - "$trial_root/before.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]));assert len(r)==1 and r[0]['execution']['state']=='RUNNING' and r[0]['execution']['stage']=='Encode',r
print(r[0]['submission']['id'])
PY
)"
    check_catalog /checks/CheckStagedCatalog.java /db/catalog.db "$id" > "$trial_root/catalog-before.json"
    compose exec -T pipeline sha256sum /inbox/movie.mkv > "$trial_root/source-before.sha256"
    compose config --format json > "$trial_root/compose.json"
    docker image inspect "$VIDYOOP_IMAGE" "$verification" > "$trial_root/images.json"
    docker inspect "$(compose ps -q pipeline)" "$(compose ps -q temporal)" "$(compose ps -q postgres)" > "$trial_root/containers-before.json"
    echo 'Prepared active encode. Force-stop only the isolated VM without stopping its containers, then restart it and run verify.'
    ;;
  verify)
    test -f "$trial_root/.viddyoop-powerloss-trial"
    test "$(cat "$trial_root/boot-before")" != "$(cat /proc/sys/kernel/random/boot_id)"
    cat /proc/sys/kernel/random/boot_id > "$trial_root/boot-after"
    compose up -d --no-build --wait --wait-timeout 120
    compose logs --no-color postgres > "$trial_root/postgres-recovery.log"
    grep -q 'automatic recovery in progress' "$trial_root/postgres-recovery.log"
    completed=false
    for attempt in $(seq 1 450); do
      if [ -f "$trial_root/library/movie.mp4" ]; then
        compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$trial_root/after.json"
        if python3 - "$trial_root" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);before=json.load(open(p/'before.json'));after=json.load(open(p/'after.json'))
ok=len(after)==1 and after[0]['submission']['id']==before[0]['submission']['id'] and after[0]['execution']['runId']==before[0]['execution']['runId'] and after[0]['execution']['state']=='COMPLETED' and after[0]['submission']['artifactState']=='committed' and after[0]['submission']['scratchReservedBytes']==0
sys.exit(0 if ok else 1)
PY
        then completed=true; break; fi
      fi
      sleep 2
    done
    test "$completed" = true
    id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["submission"]["id"])' "$trial_root/before.json")"
    check_catalog /checks/CheckRestoredCatalog.java /db/catalog.db "$id" > "$trial_root/catalog-after.json"
    compose exec -T pipeline sha256sum /inbox/movie.mkv > "$trial_root/source-after.sha256"
    cmp "$trial_root/source-before.sha256" "$trial_root/source-after.sha256"
    compose exec -T pipeline cat "/inbox/.viddyoop-cleanup/$id/done" > "$trial_root/cleanup.txt"
    grep -q '^replacement-preserved$' "$trial_root/cleanup.txt"
    compose exec -T pipeline cat /work/.ingest-reconcile.json > "$trial_root/reconcile.json"
    python3 - "$trial_root" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);before=json.load(open(p/'catalog-before.json'));after=json.load(open(p/'catalog-after.json'));pause=json.load(open(p/'reconcile.json'))
assert before['namespaceId']==after['namespaceId'] and before['submissionId']==after['submissionId']
assert pause['paused'] and pause['sources']==['/inbox/movie.mkv'],pause
PY
    compose logs --no-color > "$trial_root/services.log"
    compose exec -T pipeline java -jar /app/viddyoop.jar health
    echo 'Unclean VM restart recovered the same namespace/submission/run, one artifact/stat, verified source/output, and preserved the uncertain original.'
    ;;
  cleanup)
    test -f "$trial_root/.viddyoop-powerloss-trial"
    compose down --volumes --remove-orphans
    for directory in inbox library archive; do
      docker run --rm --network none --read-only --user 0 --mount "type=bind,src=$trial_root/$directory,dst=/cleanup" \
        --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete
    done
    rm -f "$VIDYOOP_POSTGRES_PASSWORD_FILE"
    ;;
  *) echo 'Expected prepare, verify, or cleanup' >&2; exit 2;;
esac
