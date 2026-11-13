#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
restore_compose="$PWD/docker-compose.yml"
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
restore_verification="${2:-viddyoop:verification}"
restore_mode="${4:-dev}"
case "$restore_mode" in
  dev) ;;
  server) restore_compose="$PWD/docker-compose.server.yml" ;;
  *) echo 'Expected dev or server as the fourth argument' >&2; exit 2;;
esac
if [ "$#" -ge 3 ]; then
  mkdir -p "$(dirname "$3")"
  mkdir "$3"
  restore_results="$(cd "$3" && pwd -P)"
else
  restore_results="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-restore-results.XXXXXX")"
fi
restore_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-container-restore.XXXXXX")"
restore_project="viddyoop-restore-$$"
restore_override="$restore_root/override.yaml"
export VIDYOOP_INBOX_DIR="$restore_root/inbox" VIDYOOP_LIBRARY_DIR="$restore_root/library" VIDYOOP_ARCHIVE_DIR="$restore_root/archive"
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0 VIDYOOP_NAMESPACE=default
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$restore_compose" -f "$restore_override" -p "$restore_project" "$@"; }
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then compose logs --no-color || true; fi
  compose down --volumes --remove-orphans || result=1
  docker run --rm --network none --read-only --user 0 \
    --mount "type=bind,src=$restore_root,dst=/cleanup" \
    --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete || result=1
  rm -rf "$restore_root" || result=1
  echo "Restore evidence retained at $restore_results"
  exit "$result"
}
trap cleanup EXIT
chmod 0755 "$restore_root" "$restore_results"
if [ "$restore_mode" = server ]; then
  export VIDYOOP_POSTGRES_PASSWORD_FILE="$restore_root/postgres-password"
  python3 -c 'import secrets; print(secrets.token_hex(32))' > "$VIDYOOP_POSTGRES_PASSWORD_FILE"
  chmod 0444 "$VIDYOOP_POSTGRES_PASSWORD_FILE"
fi
mkdir "$restore_root/inbox" "$restore_root/library" "$restore_root/archive" "$restore_root/quarantine" "$restore_root/fixtures"
chmod 0777 "$restore_root/inbox" "$restore_root/library" "$restore_root/archive" "$restore_root/quarantine"
cat > "$restore_override" <<'YAML'
services:
  pipeline:
    environment:
      VIDYOOP_HANDBRAKE: /work/gated-handbrake
      VIDYOOP_MIN_FREE_BYTES: "0"
      VIDYOOP_SETTLE_MS: "1000"
      VIDYOOP_POLL_MS: "100"
YAML
cat > "$restore_root/fixtures/gated-handbrake" <<'SH'
#!/bin/sh
case " $* " in
  *" --output "*)
    /usr/bin/HandBrakeCLI "$@" --encoder-preset placebo > /work/restore-progress 2>&1 &
    child=$!
    while kill -0 "$child" 2>/dev/null; do
      if grep -q 'Encoding:' /work/restore-progress; then
        kill -STOP "$child" || exit 99
        echo "$child" > /work/restore-boundary
        wait "$child"; exit $?
      fi
      sleep 0.01
    done
    wait "$child"; exit $?
    ;;
  *) exec /usr/bin/HandBrakeCLI "$@" ;;
esac
SH
cat > "$restore_root/fixtures/repaired-handbrake" <<'SH'
#!/bin/sh
exec /usr/bin/HandBrakeCLI "$@"
SH
chmod 0755 "$restore_root/fixtures/"*handbrake
runtime_jar="$(docker run --rm --network none --entrypoint sha256sum "$VIDYOOP_IMAGE" /app/viddyoop.jar)"
verification_jar="$(docker run --rm --network none --entrypoint sha256sum "$restore_verification" /app/viddyoop.jar)"
test "$runtime_jar" = "$verification_jar"
docker run --rm --network none --entrypoint ffmpeg \
  --mount "type=bind,src=$restore_root/inbox,dst=/inbox" "$VIDYOOP_IMAGE" \
  -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 \
  -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
  -t 2 -map 0:v -map 1:a -c:v mpeg4 -c:a ac3 /inbox/movie.mkv
compose run --rm --no-deps --entrypoint cp \
  --volume "$restore_root/fixtures:/fixtures:ro" pipeline /fixtures/gated-handbrake /work/gated-handbrake
compose up -d --no-build --wait --wait-timeout 120
compose config --format json > "$restore_results/compose.json"
boundary=false
for attempt in $(seq 1 60); do
  if compose exec -T pipeline test -f /work/restore-boundary; then boundary=true; break; fi
  sleep 1
done
test "$boundary" = true
compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$restore_results/before.json"
read -r restore_submission restore_run < <(python3 - "$restore_results/before.json" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
assert len(rows)==1 and rows[0]['execution']['state']=='RUNNING' and rows[0]['execution']['stage']=='Encode', rows
print(rows[0]['submission']['id'],rows[0]['execution']['runId'])
PY
)
# Kill the entire worker container/process namespace, then stop the last persistence writer.
docker update --restart=no "$(compose ps -q pipeline)" >/dev/null
compose kill -s SIGKILL pipeline
compose stop temporal
restore_pipeline_id="$(compose ps -aq pipeline)"
restore_temporal_id="$(compose ps -aq temporal)"
restore_stopped_ids=("$restore_pipeline_id" "$restore_temporal_id")
restore_recreate_services=(pipeline temporal)
if [ "$restore_mode" = server ]; then
  # A physical PostgreSQL backup requires the whole cluster stopped cleanly.
  compose stop postgres
  restore_postgres_id="$(compose ps -aq postgres)"
  restore_stopped_ids+=("$restore_postgres_id")
  restore_recreate_services+=(postgres temporal-schema temporal-namespace)
  restore_temporal_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$restore_postgres_id")"
else
  restore_temporal_volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/home/temporal"}}{{.Name}}{{end}}{{end}}' "$restore_temporal_id")"
fi
test -n "$restore_temporal_volume"
docker inspect "${restore_stopped_ids[@]}" > "$restore_results/stopped-containers.json"
python3 - "$restore_results/stopped-containers.json" "$restore_mode" <<'PY'
import json,sys
containers=json.load(open(sys.argv[1]))
assert len(containers)==(3 if sys.argv[2]=='server' else 2) and all(not c['State']['Running'] for c in containers), containers
by_service={c['Config']['Labels']['com.docker.compose.service']:c for c in containers}
assert by_service['pipeline']['State']['ExitCode']==137, by_service['pipeline']['State']
assert by_service['temporal']['State']['ExitCode']==0, by_service['temporal']['State']
if sys.argv[2]=='server':assert by_service['postgres']['State']['ExitCode']==0,by_service['postgres']['State']
PY
volume_helper() {
  local access="$1"; shift
  local program="$1"; shift
  local temporal_mount="type=volume,src=$restore_temporal_volume,dst=/temporal"
  if [ "$access" = ro ]; then temporal_mount="$temporal_mount,readonly"; fi
  docker run --rm --network none --read-only --user 0 \
    --volumes-from "$restore_pipeline_id:$access" --mount "$temporal_mount" \
    --mount "type=bind,src=$restore_results,dst=/backup" \
    --mount "type=bind,src=$restore_root/fixtures,dst=/fixtures,readonly" \
    --mount "type=bind,src=$restore_root/quarantine,dst=/quarantine" \
    --entrypoint "$program" "$VIDYOOP_IMAGE" "$@"
}
volume_helper ro tar -C / -cpf /backup/state.tar db work inbox library archive temporal
volume_helper ro sha256sum /backup/state.tar > "$restore_results/state.tar.sha256"
volume_helper ro sha256sum -c /backup/state.tar.sha256
volume_helper ro sync /backup/state.tar /backup/state.tar.sha256 /backup
if [ "$restore_mode" = server ]; then
  restore_postgres_image="$(docker inspect --format '{{.Image}}' "$restore_postgres_id")"
  docker run --rm --network none --read-only --tmpfs /checks:size=1048576,mode=1777 \
    --mount "type=bind,src=$restore_results,dst=/backup,readonly" --entrypoint sh "$restore_postgres_image" -ec \
    'tar -xf /backup/state.tar -C /checks temporal/global/pg_control temporal/PG_VERSION; pg_controldata /checks/temporal' \
    > "$restore_results/pg-control.txt"
  grep -Eq '^Database cluster state:[[:space:]]+shut down$' "$restore_results/pg-control.txt"
fi
# All six paths belong only to this uniquely named test project.
volume_helper rw find /db /work /inbox /library /archive /temporal -mindepth 1 -delete
volume_helper rw tar -C / -xpf /backup/state.tar
volume_helper rw sync
# Restore copies have new generation identities. Review/quarantine before discovery resumes.
volume_helper rw mv /inbox/movie.mkv /quarantine/restored-original.mkv
volume_helper rw cp /fixtures/repaired-handbrake /work/gated-handbrake
compose rm -f "${restore_recreate_services[@]}"
compose up -d --no-build --wait --wait-timeout 120
echo "Restored all six roots; waiting for the original workflow's production heartbeat/retry policy"
completed=false
for attempt in $(seq 1 450); do
  if [ -f "$restore_root/library/movie.mp4" ] && [ -f "$restore_root/inbox/.viddyoop-cleanup/$restore_submission/done" ]; then
    compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$restore_results/after.json"
    if python3 - "$restore_results/after.json" "$restore_submission" "$restore_run" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
assert len(rows)==1 and rows[0]['submission']['id']==sys.argv[2], rows
assert rows[0]['execution']['runId']==sys.argv[3] and rows[0]['execution']['state']=='COMPLETED', rows
assert rows[0]['submission']['artifactState']=='committed' and rows[0]['submission']['scratchReservedBytes']==0, rows
PY
    then completed=true; break; fi
  fi
  sleep 2
done
test "$completed" = true
docker run --rm --network none --read-only --cpus 0.5 --memory 512m \
  --tmpfs /tmp:exec,size=67108864,mode=1777 --volumes-from "$(compose ps -q pipeline):ro" \
  --mount "type=bind,src=$PWD/scripts/recovery,dst=/checks,readonly" \
  --env JAVA_TOOL_OPTIONS=-Xmx128m --entrypoint java "$restore_verification" \
  --class-path /app/viddyoop.jar /checks/CheckRestoredCatalog.java /db/catalog.db "$restore_submission" \
  > "$restore_results/verified.json"
compose exec -T pipeline java -jar /app/viddyoop.jar health
cat "$restore_results/after.json" "$restore_results/verified.json"
