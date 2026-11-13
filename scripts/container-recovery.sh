#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
recovery_compose="$PWD/docker-compose.yml"
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
recovery_image="${2:-viddyoop:verification}"
recovery_mode="${4:-dev}"
case "$recovery_mode" in
  dev) recovery_services=(temporal); recovery_cli_service=temporal ;;
  server)
    recovery_compose="$PWD/docker-compose.server.yml"
    recovery_services=(temporal); recovery_cli_service=temporal-namespace
    ;;
  *) echo 'Expected dev or server as the fourth argument' >&2; exit 2;;
esac
recovery_fullfs_args=(--tmpfs /fullfs:size=33554432,mode=1777)
if [ -n "${VIDYOOP_RECOVERY_FULLFS_DIR:-}" ]; then
  recovery_fullfs_dir="$(cd "$VIDYOOP_RECOVERY_FULLFS_DIR" && pwd -P)"
  recovery_fullfs_args=(--mount "type=bind,src=$recovery_fullfs_dir,dst=/fullfs")
fi
recovery_test_args=(-Dviddyoop.productionTimeouts=false)
case "${VIDYOOP_RECOVERY_PRODUCTION_TIMEOUTS:-false}" in
  false) ;;
  true) recovery_test_args=(-Dviddyoop.productionTimeouts=true -Dtest=WorkerCrashRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false) ;;
  *) echo "VIDYOOP_RECOVERY_PRODUCTION_TIMEOUTS must be true or false" >&2; exit 2 ;;
esac
if [ "$#" -ge 3 ]; then
  mkdir -p "$(dirname "$3")"
  mkdir "$3" # Refuse to mix old and new reports.
  recovery_results="$(cd "$3" && pwd -P)"
else
  recovery_results="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-recovery-results.XXXXXX")"
fi
chmod 0777 "$recovery_results"
recovery_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-container-recovery.XXXXXX")"
recovery_project="viddyoop-recovery-$$"
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0 VIDYOOP_NAMESPACE=default
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$recovery_compose" -p "$recovery_project" "$@"; }
recovery_cli_container=""
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then compose logs --no-color || true; fi
  if [ -n "$recovery_cli_container" ]; then docker rm -f "$recovery_cli_container" >/dev/null || result=1; fi
  compose down --volumes --remove-orphans || result=1
  docker run --rm --network none --read-only --user 0 \
    --mount "type=bind,src=$recovery_root,dst=/cleanup" \
    --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete || result=1
  rm -rf "$recovery_root" || result=1
  echo "Recovery reports retained at $recovery_results"
  exit "$result"
}
trap cleanup EXIT
chmod 0755 "$recovery_root"
if [ "$recovery_mode" = server ]; then
  export VIDYOOP_POSTGRES_PASSWORD_FILE="$recovery_root/postgres-password"
  python3 -c 'import secrets; print(secrets.token_hex(32))' > "$VIDYOOP_POSTGRES_PASSWORD_FILE"
  chmod 0444 "$VIDYOOP_POSTGRES_PASSWORD_FILE"
fi
mkdir "$recovery_root/fixtures" "$recovery_root/tools"
chmod 0777 "$recovery_root/fixtures"
runtime_jar="$(docker run --rm --network none --entrypoint sha256sum "$VIDYOOP_IMAGE" /app/viddyoop.jar)"
verification_jar="$(docker run --rm --network none --entrypoint sha256sum "$recovery_image" /app/viddyoop.jar)"
test "$runtime_jar" = "$verification_jar"
docker image inspect "$VIDYOOP_IMAGE" "$recovery_image" > "$recovery_results/images.json"
compose up -d --no-build --wait --wait-timeout 120 "${recovery_services[@]}"
if [ "$recovery_mode" = server ]; then
  # Compose --wait expects a targeted service to remain running, so execute the
  # one-shot namespace initializer separately after the backend becomes healthy.
  compose run --rm --no-deps temporal-namespace
fi
compose config --format json > "$recovery_results/compose.json"
recovery_temporal_image="$(python3 - "$recovery_results/compose.json" "$recovery_cli_service" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['services'][sys.argv[2]]['image'])
PY
)"
# Reuse the selected stack's digest-pinned CLI image.
recovery_cli_container="$(docker create "$recovery_temporal_image")"
docker cp "$recovery_cli_container:/usr/local/bin/temporal" "$recovery_root/tools/temporal"
docker rm "$recovery_cli_container" >/dev/null
recovery_cli_container=""
chmod 0755 "$recovery_root/tools/temporal"
docker run --rm --network none --entrypoint ffmpeg \
  --mount "type=bind,src=$recovery_root/fixtures,dst=/fixtures" "$VIDYOOP_IMAGE" \
  -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 \
  -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
  -t 2 -map 0:v -map 1:a -c:v mpeg4 -c:a ac3 /fixtures/movie.mkv
docker run --rm --init --cpus 2 --memory 4g --network "${recovery_project}_default" \
  --tmpfs /tmp:exec,size=536870912,mode=1777 "${recovery_fullfs_args[@]}" \
  --mount "type=bind,src=$recovery_root/fixtures,dst=/fixtures,readonly" \
  --mount "type=bind,src=$recovery_root/tools,dst=/tools,readonly" \
  --mount "type=bind,src=$recovery_results,dst=/results" \
  --env JAVA_TOOL_OPTIONS=-Xmx512m --entrypoint sh "$recovery_image" -c '
    df -PT /fullfs
    /usr/share/maven/bin/mvn -Dmaven.repo.local=/m2 -B -ntp -pl java/modules/ViddyoopCore -am verify \
      -Dviddyoop.jar=/app/viddyoop.jar -Dviddyoop.temporal=temporal:7233 \
      -Dviddyoop.temporal.cli=/tools/temporal -Dviddyoop.fullfs=/fullfs \
      -Dviddyoop.handbrake=/usr/bin/HandBrakeCLI -Dviddyoop.fixture=/fixtures/movie.mkv \
      -Dviddyoop.mkvmerge=/usr/bin/mkvmerge -Dviddyoop.mkvextract=/usr/bin/mkvextract \
      -Dviddyoop.ffmpeg=/usr/bin/ffmpeg "$@"
    result=$?
    for module in RKUtils HBXUtils ViddyoopCore; do
      mkdir -p "/results/$module"
      if [ -d "/src/java/modules/$module/target/surefire-reports" ]; then
        cp -a "/src/java/modules/$module/target/surefire-reports" "/results/$module/" || result=1
      fi
    done
    cp -a /src/java/modules/ViddyoopCore/target/site/jacoco /results/ViddyoopCore/ || result=1
    find /results -mindepth 1 -type d -exec chmod a+rwx {} + || result=1
    exit "$result"
  ' recovery "${recovery_test_args[@]}" 2>&1 | tee "$recovery_results/tests.log"
python3 - "$recovery_results/ViddyoopCore/surefire-reports" "${VIDYOOP_RECOVERY_PRODUCTION_TIMEOUTS:-false}" <<'PY'
import pathlib,sys,xml.etree.ElementTree as E
reports=list(pathlib.Path(sys.argv[1]).glob('TEST-*.xml'))
assert reports, 'Core test reports missing'
counts={k:0 for k in ['tests','failures','errors','skipped']}
seen=set()
for report in reports:
    suite=E.parse(report).getroot()
    seen.add(suite.get('name','').split('.')[-1])
    for key in counts:counts[key]+=int(suite.get(key,'0'))
assert counts['tests']>0 and all(counts[k]==0 for k in ['failures','errors','skipped']), counts
required={'WorkerCrashRecoveryTest'}
if sys.argv[2]!='true':
    required|={'ColdRestoreTest','DiskFullRecoveryTest','RunnablePipelineTest',
               'RetryCommandIntegrationTest','ItunesImportDaemonTest','ItunesIndexScheduleTest',
               'NamespaceBindingIntegrationTest','TemporalCatalogBindingTest'}
assert required<=seen, ('Recovery gates missing',required-seen)
print('Pinned-container recovery results:',counts,'production timeouts:',sys.argv[2])
PY
