#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
smoke_compose="$PWD/docker-compose.yml"
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
smoke_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-container.XXXXXX")"
case "${2:-dev}" in
  dev) ;;
  server)
    smoke_compose="$PWD/docker-compose.server.yml"
    export VIDYOOP_POSTGRES_PASSWORD_FILE="$smoke_root/postgres-password"
    python3 -c 'import secrets; print(secrets.token_hex(32))' > "$VIDYOOP_POSTGRES_PASSWORD_FILE"
    chmod 0444 "$VIDYOOP_POSTGRES_PASSWORD_FILE"
    ;;
  *) echo 'Expected dev or server as the second argument' >&2; exit 2;;
esac
smoke_project="viddyoop-smoke-$$"
export VIDYOOP_INBOX_DIR="$smoke_root/inbox" VIDYOOP_LIBRARY_DIR="$smoke_root/library" VIDYOOP_ARCHIVE_DIR="$smoke_root/archive"
export VIDYOOP_MIN_FREE_BYTES=0 VIDYOOP_SETTLE_MS=1000 VIDYOOP_POLL_MS=100
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0
export VIDYOOP_NAMESPACE=default
# Do not inherit a developer's Compose files, optional import profile, or .env.
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$smoke_compose" -p "$smoke_project" "$@"; }
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then compose logs --no-color || true; fi
  compose down --volumes --remove-orphans || result=1
  # A rootless engine maps container UID 1000 to a subordinate host UID. Use
  # its own namespace to unlink test-owned cleanup receipts before host removal.
  if docker image inspect "$VIDYOOP_IMAGE" >/dev/null 2>&1; then
    docker run --rm --network none --read-only --user 0 \
      --mount "type=bind,src=$smoke_root,dst=/cleanup" \
      --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete || result=1
  fi
  rm -rf "$smoke_root" || result=1
  exit "$result"
}
trap cleanup EXIT
chmod 0755 "$smoke_root"
mkdir -p "$VIDYOOP_INBOX_DIR" "$VIDYOOP_LIBRARY_DIR" "$VIDYOOP_ARCHIVE_DIR"
chmod 0777 "$VIDYOOP_INBOX_DIR" "$VIDYOOP_LIBRARY_DIR" "$VIDYOOP_ARCHIVE_DIR"
docker run --rm --entrypoint HandBrakeCLI "$VIDYOOP_IMAGE" --version 2>&1 | tee "$smoke_root/handbrake-version"
grep -Eq 'HandBrake 1\.6\.' "$smoke_root/handbrake-version"
cat > "$smoke_root/subtitle.srt" <<'SRT'
1
00:00:00,100 --> 00:00:01,500
Container subtitle fixture

SRT
# Read-only fixture input; outputs go to the disposable world-writable inbox.
docker run --rm -v "$smoke_root:/data:ro" -v "$VIDYOOP_INBOX_DIR:/inbox" --entrypoint ffmpeg "$VIDYOOP_IMAGE" \
  -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 \
  -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 -i /data/subtitle.srt \
  -t 2 -map 0:v -map 1:a -map 2:s -c:v mpeg4 -c:a ac3 -c:s srt \
  -metadata:s:s:0 language=eng /inbox/smoke.mkv
compose up -d --no-build --wait --wait-timeout 120
compose config --format json > "$smoke_root/compose.json"
docker inspect "$(compose ps -q pipeline)" "$(compose ps -q temporal)" > "$smoke_root/containers.json"
compose exec -T pipeline sh -c '
  if [ -f /sys/fs/cgroup/memory.max ]; then
    cat /sys/fs/cgroup/memory.max
    cat /sys/fs/cgroup/cpu.max
  else
    cat /sys/fs/cgroup/memory/memory.limit_in_bytes
    printf "%s %s\n" "$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us)" "$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us)"
  fi
  stat -f -c "%S %b" /tmp
' > "$smoke_root/resources.txt"
python3 - "$smoke_root/compose.json" "$smoke_root/containers.json" "$smoke_root/resources.txt" <<'PY'
import decimal,json,sys
services=json.load(open(sys.argv[1]))['services']
for container in json.load(open(sys.argv[2])):
    service=container['Config']['Labels']['com.docker.compose.service']
    expected=services[service]
    actual=container['HostConfig']
    assert actual['Memory']==int(expected['mem_limit']), (service,actual['Memory'])
    assert actual['NanoCpus']==int(decimal.Decimal(str(expected['cpus']))*10**9), service
memory,cpu,tmp=open(sys.argv[3]).read().splitlines()
assert int(memory)==int(services['pipeline']['mem_limit']), memory
quota,period=map(int,cpu.split())
assert decimal.Decimal(quota)/period==decimal.Decimal(str(services['pipeline']['cpus'])), cpu
block_size,blocks=map(int,tmp.split())
assert block_size*blocks==67108864, tmp
print('Container resource configuration and pipeline cgroup/tmpfs limits verified')
PY
completed=false
for attempt in $(seq 1 90); do
  if [ -f "$VIDYOOP_LIBRARY_DIR/smoke.mp4" ] && [ ! -f "$VIDYOOP_INBOX_DIR/smoke.mkv" ]; then
    compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$smoke_root/status.json"
    if python3 - "$smoke_root/status.json" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
assert len(rows)==1 and rows[0]['execution']['state']=='COMPLETED'
assert rows[0]['submission']['artifactState']=='committed'
PY
    then completed=true; break; fi
  fi
  sleep 1
done
[ "$completed" = true ]
docker run --rm -v "$VIDYOOP_LIBRARY_DIR:/library:ro" --entrypoint ffprobe "$VIDYOOP_IMAGE" \
  -v error -show_entries stream=codec_name,codec_type -of json /library/smoke.mp4 > "$smoke_root/streams.json"
python3 - "$smoke_root/streams.json" <<'PY'
import json,sys
streams={(s['codec_type'],s['codec_name']) for s in json.load(open(sys.argv[1]))['streams']}
assert {('video','h264'),('audio','aac'),('audio','ac3'),('subtitle','mov_text')} <= streams, streams
PY
compose exec -T pipeline java -jar /app/viddyoop.jar health
if [ "${2:-dev}" = server ]; then
  # Recreate the setup jobs against the populated database, verifying schema
  # setup is idempotent and persisted history survives a full backend restart.
  compose stop pipeline temporal postgres
  compose rm -f temporal-schema temporal-namespace
  compose up -d --no-build --wait --wait-timeout 120
  compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$smoke_root/status-restarted.json"
  python3 - "$smoke_root/status.json" "$smoke_root/status-restarted.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))==json.load(open(sys.argv[2])), 'Backend restart changed submission/history/artifact identity'
PY
  compose exec -T pipeline java -jar /app/viddyoop.jar health
fi
cat "$smoke_root/status.json" "$smoke_root/streams.json"
