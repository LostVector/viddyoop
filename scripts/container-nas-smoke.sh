#!/usr/bin/env bash
# Exercise the deployment overlay with a disposable external volume. No NAS needed.
set -euo pipefail
cd "$(dirname "$0")/.."
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
trial_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-nas-deploy.XXXXXX")"
trial_project="viddyoop-nas-check-$$"
export VIDYOOP_NAS_VOLUME="$trial_project-media"
export VIDYOOP_NAS_SUBPATH=isolated
export VIDYOOP_POSTGRES_PASSWORD_FILE="$trial_root/password"
printf 'unused-nas-check-fixture\n' > "$VIDYOOP_POSTGRES_PASSWORD_FILE"
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -p "$trial_project" -f docker-compose.server.yml -f deploy/compose.nas.yml "$@"; }
cleanup() {
    result=$?
    compose down --volumes --remove-orphans || result=1
    docker volume rm "$VIDYOOP_NAS_VOLUME" || result=1
    rm -rf "$trial_root"
    exit "$result"
}
docker volume create "$VIDYOOP_NAS_VOLUME" >/dev/null
trap cleanup EXIT
docker run --rm --network none --user 0 --mount "type=volume,src=$VIDYOOP_NAS_VOLUME,dst=/fixture" \
    --entrypoint sh "$VIDYOOP_IMAGE" -ec '
      mkdir -p /fixture/isolated/inbox /fixture/isolated/work /fixture/isolated/library /fixture/isolated/archive
      printf "unrelated data\n" > /fixture/unrelated
      chown -R 1000:1000 /fixture/isolated'
compose config --format json > "$trial_root/config.json"
python3 - "$trial_root/config.json" "$VIDYOOP_NAS_VOLUME" <<'PY'
import json, sys
c = json.load(open(sys.argv[1])); p = c['services']['pipeline']; mounts = p['volumes']
assert len(mounts) == 2 and all(m['type'] == 'volume' for m in mounts), mounts
media = next(m for m in mounts if m['target'] == '/data')
assert media['source'] == 'nas-public-temp' and media['volume']['subpath'] == 'isolated'
assert next(m for m in mounts if m['target'] == '/db')['source'] == 'catalog-data'
assert c['volumes']['nas-public-temp']['external'] is True
assert c['volumes']['nas-public-temp']['name'] == sys.argv[2]
assert p['depends_on']['nas-check']['condition'] == 'service_completed_successfully'
assert p['depends_on']['temporal-namespace']['condition'] == 'service_completed_successfully'
for name in ['inbox', 'work', 'library', 'archive']:
    assert p['environment']['VIDYOOP_' + name.upper()] == '/data/' + name
importer = c['services'].get('itunes-import')
assert importer is None or importer['profiles'] == ['itunes-import']
PY
if compose run --rm --no-deps nas-check > "$trial_root/missing-marker.log" 2>&1; then
    echo 'NAS check accepted a missing isolation marker' >&2; exit 1
fi
docker run --rm --network none --user 0 --mount "type=volume,src=$VIDYOOP_NAS_VOLUME,dst=/fixture" \
    --entrypoint touch "$VIDYOOP_IMAGE" /fixture/isolated/.viddyoop-media-root
docker run --rm --network none --user 0 --mount "type=volume,src=$VIDYOOP_NAS_VOLUME,dst=/fixture" \
    --entrypoint chmod "$VIDYOOP_IMAGE" 0555 /fixture/isolated/work
if compose run --rm --no-deps nas-check > "$trial_root/read-only-work.log" 2>&1; then
    echo 'NAS check accepted an unwritable work directory' >&2; exit 1
fi
docker run --rm --network none --user 0 --mount "type=volume,src=$VIDYOOP_NAS_VOLUME,dst=/fixture" \
    --entrypoint chmod "$VIDYOOP_IMAGE" 0755 /fixture/isolated/work
compose run --rm --no-deps nas-check
docker run --rm --network none --read-only --mount "type=volume,src=$VIDYOOP_NAS_VOLUME,dst=/fixture,readonly" \
    --entrypoint sh "$VIDYOOP_IMAGE" -ec '
      test "$(cat /fixture/unrelated)" = "unrelated data"
      test ! -e /fixture/inbox && test ! -e /fixture/work
      test -z "$(ls -A /fixture/isolated/work)"'
echo 'NAS deployment overlay: isolation, missing-marker refusal and non-root writes passed.'
