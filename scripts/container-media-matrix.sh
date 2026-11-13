#!/usr/bin/env bash
# Short generated compatibility fixtures; these do not replace real collection trials.
set -euo pipefail
cd "$(dirname "$0")/.."
matrix_compose="$PWD/docker-compose.yml"
export VIDYOOP_IMAGE="${1:-viddyoop:test}"
if [ "$#" -ge 2 ]; then
  mkdir -p "$(dirname "$2")"
  mkdir "$2"
  matrix_results="$(cd "$2" && pwd -P)"
else
  matrix_results="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-media-results.XXXXXX")"
fi
matrix_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-media-matrix.XXXXXX")"
matrix_project="viddyoop-media-$$"
export VIDYOOP_INBOX_DIR="$matrix_root/inbox" VIDYOOP_LIBRARY_DIR="$matrix_root/library" VIDYOOP_ARCHIVE_DIR="$matrix_root/archive"
export VIDYOOP_MIN_FREE_BYTES=0 VIDYOOP_SETTLE_MS=1000 VIDYOOP_POLL_MS=100 VIDYOOP_ARCHIVE_POLICY=none
export VIDYOOP_TEMPORAL_PORT=0 VIDYOOP_TEMPORAL_UI_PORT=0 VIDYOOP_NAMESPACE=default
compose() { COMPOSE_PROFILES= docker compose --env-file /dev/null -f "$matrix_compose" -p "$matrix_project" "$@"; }
cleanup() {
  result=$?
  compose logs --no-color > "$matrix_results/services.log" || result=1
  compose down --volumes --remove-orphans || result=1
  docker run --rm --network none --read-only --user 0 --mount "type=bind,src=$matrix_root,dst=/cleanup" \
    --entrypoint find "$VIDYOOP_IMAGE" /cleanup -mindepth 1 -delete || result=1
  rm -rf "$matrix_root" || result=1
  echo "Media matrix reports retained at $matrix_results"
  exit "$result"
}
trap cleanup EXIT
chmod 0755 "$matrix_root" "$matrix_results"
mkdir "$matrix_root/inbox" "$matrix_root/library" "$matrix_root/archive"
chmod 0777 "$matrix_root/inbox" "$matrix_root/library" "$matrix_root/archive"
media_ffmpeg() {
  docker run --rm --network none --cpus 1 --memory 512m \
    --mount "type=bind,src=$matrix_root,dst=/data" --entrypoint ffmpeg "$VIDYOOP_IMAGE" \
    -hide_banner -loglevel error -threads 1 -filter_threads 1 "$@"
}
media_probe() {
  docker run --rm --network none --read-only --mount "type=bind,src=$matrix_root,dst=/data,readonly" \
    --entrypoint ffprobe "$VIDYOOP_IMAGE" -v error -show_streams -show_format -of json "$1"
}
cat > "$matrix_root/subtitle.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,28,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.10,0:00:01.80,Default,,0,0,0,,Matrix ASS subtitle
ASS
media_ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=24 -f lavfi -i anullsrc=channel_layout=5.1:sample_rate=48000 \
  -t 2 -map 0:v -map 1:a -c:v mpeg4 -threads 1 -c:a dca -strict -2 /data/inbox/hd-dts.mkv
media_ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30000/1001 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -t 2 -map 0:v -map 1:a -c:v mpeg2video -threads 1 -flags +ilme+ildct -top 1 -c:a mp2 /data/inbox/transport.ts
media_ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=24 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 -i /data/subtitle.ass \
  -t 2 -map 0:v -map 1:a -map 2:s -c:v libx265 -threads 1 -preset ultrafast -pix_fmt yuv420p10le \
  -x265-params pools=1:frame-threads=1 -c:a aac -c:s ass -metadata:s:s:0 language=eng /data/inbox/hevc-ass.mkv
media_ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -t 2 -an -c:v mpeg4 -threads 1 /data/inbox/silent.mp4
for fixture in hd-dts.mkv transport.ts hevc-ass.mkv silent.mp4; do
  media_probe "/data/inbox/$fixture" > "$matrix_results/source-$fixture.json"
done
docker image inspect "$VIDYOOP_IMAGE" > "$matrix_results/images.json"
compose up -d --no-build --wait --wait-timeout 120 temporal pipeline
compose config --format json > "$matrix_results/compose.json"
completed=false
for attempt in $(seq 1 90); do
  compose exec -T pipeline java -jar /app/viddyoop.jar status --json > "$matrix_results/status.json"
  if python3 - "$matrix_results/status.json" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))
assert len(rows)==4 and all(r['execution']['state']=='COMPLETED' and r['submission']['artifactState']=='committed' and r['submission']['scratchReservedBytes']==0 for r in rows)
PY
  then completed=true; break; fi
  sleep 2
done
test "$completed" = true
for name in hd-dts transport hevc-ass silent; do
  test -f "$matrix_root/library/$name.mp4"
  media_probe "/data/library/$name.mp4" > "$matrix_results/output-$name.json"
  media_ffmpeg -i "/data/library/$name.mp4" -map 0:v -map '0:a?' -f null -
done
media_ffmpeg -i /data/library/hevc-ass.mp4 -map 0:s:0 -f srt - > "$matrix_results/subtitle.srt"
python3 - "$matrix_root" "$matrix_results" <<'PY'
import json,pathlib,sys
root,reports=map(pathlib.Path,sys.argv[1:]);summary={}
for name,size,audio,subtitles in [('hd-dts',(1920,1080),{('aac',2),('ac3',6)},0),('transport',(1280,720),{('aac',2)},0),('hevc-ass',(1280,720),{('aac',2)},1),('silent',(640,360),set(),0)]:
 p=json.load(open(reports/f'output-{name}.json'));streams=p['streams']
 video=[s for s in streams if s['codec_type']=='video'];assert len(video)==1
 assert video[0]['codec_name']=='h264' and (video[0]['width'],video[0]['height'])==size,(name,video)
 assert video[0].get('field_order')=='progressive',(name,video)
 assert {(s['codec_name'],s['channels']) for s in streams if s['codec_type']=='audio'}==audio,(name,streams)
 subs=[s for s in streams if s['codec_type']=='subtitle'];assert len(subs)==subtitles,(name,subs)
 if subtitles:assert subs[0]['codec_name']=='mov_text' and subs[0]['tags']['language']=='eng',subs
 assert 1.5<float(p['format']['duration'])<3.0,(name,p['format'])
 summary[name]={'width':size[0],'height':size[1],'audio':sorted(audio),'subtitles':subtitles,'duration':p['format']['duration'],'decoded':True}
assert 'Matrix ASS subtitle' in (reports/'subtitle.srt').read_text()
assert not list((root/'inbox').glob('*.mkv')) and not list((root/'inbox').glob('*.ts')) and not list((root/'inbox').glob('*.mp4'))
assert json.load(open(reports/'source-hd-dts.mkv.json'))['streams'][1]['codec_name']=='dts'
source=json.load(open(reports/'source-hevc-ass.mkv.json'))['streams'][0]
assert source['codec_name']=='hevc' and source['pix_fmt']=='yuv420p10le',source
source=json.load(open(reports/'source-transport.ts.json'))['streams'][0]
assert source['codec_name']=='mpeg2video' and source.get('field_order') not in (None,'progressive'),source
(reports/'summary.json').write_text(json.dumps({'scope':'Short synthetic fixtures; real collection trial still required','cases':summary},indent=2)+'\n')
print(json.dumps(summary))
PY
compose exec -T pipeline java -jar /app/viddyoop.jar health
