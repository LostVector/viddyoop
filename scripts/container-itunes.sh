#!/usr/bin/env bash
# Synthetic app/export integration only: never accesses a real host media library.
set -euo pipefail
cd "$(dirname "$0")/.."
itunes_compose="$PWD/docker-compose.yml"
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
if [ "$#" -ge 2 ]; then
  mkdir -p "$(dirname "$2")"
  mkdir "$2"
  itunes_results="$(cd "$2" && pwd -P)"
else
  itunes_results="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-itunes-results.XXXXXX")"
fi
itunes_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-container-itunes.XXXXXX")"
itunes_project="viddyoop-itunes-$$"
export VIDYOOP_INBOX_DIR="$itunes_root/inbox" VIDYOOP_LIBRARY_DIR="$itunes_root/library" VIDYOOP_ARCHIVE_DIR="$itunes_root/archive"
export VIDYOOP_ITUNES_LIBRARY_DIR="$itunes_root/export" VIDYOOP_ITUNES_MEDIA_DIR="$itunes_root/media" VIDYOOP_ITUNES_DELIVERY_DIR="$itunes_root/delivery"
export VIDYOOP_ITUNES_XML_NAME=library.xml VIDYOOP_ITUNES_HOST_MEDIA_ROOT=/Host/Media
export VIDYOOP_ITUNES_ADD_NAME="Automatically Add to iTunes.localized"
export VIDYOOP_ITUNES_REJECTED=/itunes-delivery/rejected VIDYOOP_ITUNES_RECONCILE_ONLY=false
export VIDYOOP_ITUNES_INDEX_INTERVAL_MINUTES=1 VIDYOOP_ITUNES_IMPORT_CHECK_SECONDS=1 VIDYOOP_ITUNES_IMPORT_SCAN_SECONDS=1
export VIDYOOP_MIN_FREE_BYTES=0 VIDYOOP_SETTLE_MS=1000 VIDYOOP_POLL_MS=100 VIDYOOP_NAMESPACE=default
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$itunes_compose" --profile itunes-import -p "$itunes_project" "$@"; }
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then compose logs --no-color || true; fi
  compose down --volumes --remove-orphans || result=1
  docker run --rm --network none --read-only --user 0 \
    --mount "type=bind,src=$itunes_root,dst=/cleanup" \
    --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete || result=1
  rm -rf "$itunes_root" || result=1
  echo "Synthetic iTunes profile evidence retained at $itunes_results"
  exit "$result"
}
trap cleanup EXIT
chmod 0755 "$itunes_root" "$itunes_results"
mkdir "$itunes_root/inbox" "$itunes_root/library" "$itunes_root/archive" "$itunes_root/export" "$itunes_root/media" "$itunes_root/delivery"
mkdir "$itunes_root/delivery/$VIDYOOP_ITUNES_ADD_NAME" "$itunes_root/delivery/rejected"
chmod 0777 "$itunes_root/inbox" "$itunes_root/library" "$itunes_root/archive" "$itunes_root/media" "$itunes_root/delivery" \
  "$itunes_root/delivery/$VIDYOOP_ITUNES_ADD_NAME" "$itunes_root/delivery/rejected"
cat > "$itunes_root/export/library.xml" <<'XML'
<plist><dict><key>Library Persistent ID</key><string>COMPOSE-LIB</string><key>Tracks</key><dict/></dict></plist>
XML
drop_fixture() {
  docker run --rm --network none --entrypoint ffmpeg \
    --mount "type=bind,src=$itunes_root/inbox,dst=/inbox" "$VIDYOOP_IMAGE" \
    -hide_banner -loglevel error -f lavfi -i "$2" \
    -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
    -t 2 -map 0:v -map 1:a -c:v mpeg4 -c:a ac3 "/inbox/$1.mkv"
}
submission_for() {
  python3 - "$itunes_results/status.json" "$1" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
matches=[r for r in rows if r['submission']['source']=='/inbox/'+sys.argv[2]+'.mkv']
assert len(matches)==1, rows
print(matches[0]['submission']['id'])
PY
}
wait_library() {
  for attempt in $(seq 1 120); do
    if [ -f "$itunes_root/library/$1.mp4" ]; then
      compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$itunes_results/status.json"
      return 0
    fi
    sleep 1
  done
  return 1
}
wait_outcome() {
  for attempt in $(seq 1 120); do
    compose exec -T pipeline java -jar /app/viddyoop.jar status --submission "$1" --json > "$itunes_results/status.json"
    if python3 - "$itunes_results/status.json" "$2" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
delivery=rows[0]['submission'].get('delivery') if len(rows)==1 else None
sys.exit(0 if delivery and delivery['outcome']==sys.argv[2] else 1)
PY
    then return 0; fi
    sleep 2
  done
  cat "$itunes_results/status.json" >&2
  return 1
}
checksum() {
  # Read through the owning container UID; a rootless host UID may differ.
  compose exec -T pipeline sha256sum "/library/$1.mp4"
}
compose up -d --no-build --wait --wait-timeout 120
docker inspect "$(compose ps -q itunes-index)" "$(compose ps -q itunes-import)" > "$itunes_results/containers.json"
python3 - "$itunes_results/containers.json" <<'PY'
import json,sys
for container in json.load(open(sys.argv[1])):
    assert container['HostConfig']['ReadonlyRootfs'], container['Name']
    mounts={m['Destination']:m for m in container['Mounts']}
    if container['Config']['Labels']['com.docker.compose.service']=='itunes-import':
        assert not mounts['/library']['RW'] and not mounts['/itunes-media']['RW'] and mounts['/itunes-delivery']['RW']
    else:assert not mounts['/itunes-lib']['RW']
PY
drop_fixture movie testsrc2=size=320x180:rate=24
wait_library movie
itunes_submission="$(submission_for movie)"
wait_outcome "$itunes_submission" DELIVERED
cp "$itunes_results/status.json" "$itunes_results/delivered.json"
itunes_watched="$itunes_root/delivery/$VIDYOOP_ITUNES_ADD_NAME/viddyoop-$itunes_submission.mp4"
test -f "$itunes_watched"
itunes_checksum="$(checksum movie)"
mv "$itunes_watched" "$itunes_root/media/imported.mp4"
wait_outcome "$itunes_submission" AMBIGUOUS
cp "$itunes_results/status.json" "$itunes_results/ambiguous.json"
compose restart itunes-import
cat > "$itunes_root/export/library.xml.tmp" <<'XML'
<plist><dict><key>Library Persistent ID</key><string>COMPOSE-LIB</string><key>Tracks</key><dict>
<key>7</key><dict><key>Persistent ID</key><string>COMPOSE-TRACK</string><key>Name</key><string>Imported</string>
<key>Total Time</key><integer>2000</integer><key>Location</key><string>file:///Host/Media/imported.mp4</string></dict>
</dict></dict></plist>
XML
mv "$itunes_root/export/library.xml.tmp" "$itunes_root/export/library.xml"
# Confirmation must come from the recurring index worker, not a manual index command.
wait_outcome "$itunes_submission" CONFIRMED
cp "$itunes_results/status.json" "$itunes_results/confirmed.json"
test ! -e "$itunes_watched"
test "$itunes_checksum" = "$(checksum movie)"
drop_fixture rejected color=c=blue:size=320x180:rate=24
wait_library rejected
itunes_rejected_id="$(submission_for rejected)"
wait_outcome "$itunes_rejected_id" DELIVERED
itunes_rejected_name="viddyoop-$itunes_rejected_id.mp4"
mv "$itunes_root/delivery/$VIDYOOP_ITUNES_ADD_NAME/$itunes_rejected_name" "$itunes_root/delivery/rejected/$itunes_rejected_name"
wait_outcome "$itunes_rejected_id" REJECTED
cp "$itunes_results/status.json" "$itunes_results/rejected.json"
test -f "$itunes_root/delivery/rejected/$itunes_rejected_name"
test ! -e "$itunes_root/delivery/$VIDYOOP_ITUNES_ADD_NAME/$itunes_rejected_name"
test -f "$itunes_root/library/rejected.mp4"
compose exec -T pipeline java -jar /app/viddyoop.jar health
cat "$itunes_results/confirmed.json" "$itunes_results/rejected.json"
