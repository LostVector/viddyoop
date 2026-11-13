#!/usr/bin/env bash
# Two-phase synthetic shared-inbox trial. Reboot the Docker VM between phases.
set -euo pipefail
cd "$(dirname "$0")/.."
trial_compose="$PWD/docker-compose.yml"
phase="${1:?prepare, verify, or cleanup}"
trial_root="${2:?absolute path to a new directory on a disposable shared mount}"
case "$trial_root" in /*) ;; *) echo 'An absolute fixture path is required' >&2; exit 2;; esac
export VIDYOOP_IMAGE="${3:-viddyoop:test}"
trial_verification="${4:-viddyoop:verification}"
trial_project="viddyoop-remount-$(printf '%s' "$trial_root" | sha256sum | cut -c1-12)"
export VIDYOOP_INBOX_DIR="$trial_root/inbox" VIDYOOP_LIBRARY_DIR="$trial_root/library" VIDYOOP_ARCHIVE_DIR="$trial_root/archive"
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0 VIDYOOP_NAMESPACE=default
export VIDYOOP_MIN_FREE_BYTES=0 VIDYOOP_SETTLE_MS=1000 VIDYOOP_POLL_MS=100 VIDYOOP_ARCHIVE_POLICY=none
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$trial_compose" -p "$trial_project" "$@"; }
check_java() {
  docker run --rm --network "${trial_project}_default" --read-only --tmpfs /tmp:exec,size=67108864,mode=1777 \
    --volumes-from "$(compose ps -aq pipeline)":rw \
    --mount "type=bind,src=$PWD/scripts/recovery,dst=/checks,readonly" \
    --env JAVA_TOOL_OPTIONS=-Xmx128m --entrypoint java "$trial_verification" --class-path /app/viddyoop.jar "$@"
}
case "$phase" in
  prepare)
    mkdir "$trial_root" # Refuse to overwrite an earlier trial.
    chmod 0755 "$trial_root"
    touch "$trial_root/.viddyoop-remount-trial"
    mkdir "$trial_root/inbox" "$trial_root/library" "$trial_root/archive" "$trial_root/quarantine"
    chmod 0777 "$trial_root"/{inbox,library,archive,quarantine}
    docker run --rm --network none --mount "type=bind,src=$trial_root/inbox,dst=/inbox" \
      --entrypoint ffmpeg "$VIDYOOP_IMAGE" -hide_banner -loglevel error \
      -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
      -t 2 -map 0:v -map 1:a -c:v mpeg4 -c:a ac3 /inbox/movie.mkv
    compose create temporal pipeline
    compose up -d --no-build --wait --wait-timeout 120 temporal
    check_java /checks/PrepareSharedSubmission.java > "$trial_root/prepared.json"
    cat /proc/sys/kernel/random/boot_id > "$trial_root/boot-before"
    docker image inspect "$VIDYOOP_IMAGE" "$trial_verification" > "$trial_root/images.json"
    echo "Prepared. Reboot the Docker VM, then run verify with the same arguments."
    ;;
  verify)
    test -f "$trial_root/.viddyoop-remount-trial"
    test "$(cat "$trial_root/boot-before")" != "$(cat /proc/sys/kernel/random/boot_id)"
    cat /proc/sys/kernel/random/boot_id > "$trial_root/boot-after"
    compose up -d --no-build --wait --wait-timeout 120 temporal pipeline
    completed=false
    for attempt in $(seq 1 90); do
      compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$trial_root/status-after.json"
      if python3 - "$trial_root" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); rows=json.load(open(p/'status-after.json'))
assert len(rows)==1 and rows[0]['execution']['state']=='COMPLETED'
assert rows[0]['submission']['id']==json.load(open(p/'prepared.json'))['submissionId']
assert (p/'inbox/movie.mkv').is_file()
PY
      then completed=true; break; fi
      sleep 1
    done
    test "$completed" = true
    compose exec -T pipeline cat /work/.ingest-reconcile.json > "$trial_root/reconcile.json"
    python3 - "$trial_root/reconcile.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1])); assert r['paused'] and r['sources']==['/inbox/movie.mkv'], r
PY
    trial_id="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["submissionId"])' "$trial_root/prepared.json")"
    check_java /checks/CheckRestoredCatalog.java /db/catalog.db "$trial_id" > "$trial_root/catalog-check.json"
    compose exec -T pipeline sh -c 'find /inbox/.viddyoop-cleanup -name done -exec cat {} \;' > "$trial_root/cleanup-receipts.txt"
    grep -q replacement-preserved "$trial_root/cleanup-receipts.txt"
    # An unregistered arrival must remain pending while old identities need review.
    docker run --rm --network none --user 0 --mount "type=bind,src=$trial_root,dst=/trial" --entrypoint cp "$VIDYOOP_IMAGE" /trial/inbox/movie.mkv /trial/inbox/next.mkv
    sleep 3
    compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$trial_root/status-paused.json"
    python3 - "$trial_root/status-paused.json" <<'PY'
import json,sys
assert len(json.load(open(sys.argv[1])))==1
PY
    test ! -e "$trial_root/library/next.mp4"
    docker run --rm --network none --user 0 --mount "type=bind,src=$trial_root,dst=/trial" --entrypoint mv "$VIDYOOP_IMAGE" /trial/inbox/movie.mkv /trial/quarantine/movie.mkv
    completed=false
    for attempt in $(seq 1 90); do
      compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$trial_root/status-resumed.json"
      if python3 - "$trial_root" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); rows=json.load(open(p/'status-resumed.json'))
assert len(rows)==2 and all(r['execution']['state']=='COMPLETED' for r in rows)
assert (p/'quarantine/movie.mkv').is_file() and not (p/'inbox/next.mkv').exists()
assert (p/'library/next.mp4').is_file()
PY
      then completed=true; break; fi
      sleep 1
    done
    test "$completed" = true
    compose exec -T pipeline test ! -e /work/.ingest-reconcile.json
    compose logs --no-color > "$trial_root/services.log"
    echo 'Shared-inbox reboot: one pending submission recovered, original preserved, intake paused and resumed after quarantine.'
    ;;
  cleanup)
    test -f "$trial_root/.viddyoop-remount-trial"
    compose down --volumes --remove-orphans
    # Preserve reports; delete only this trial's generated media and cleanup receipts.
    for directory in inbox library archive quarantine; do
      docker run --rm --network none --read-only --user 0 --mount "type=bind,src=$trial_root/$directory,dst=/cleanup" \
        --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete
    done
    ;;
  *) echo 'Expected prepare, verify, or cleanup' >&2; exit 2;;
esac
