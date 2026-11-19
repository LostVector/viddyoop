#!/usr/bin/env bash
# Verify prefix compatibility without starting a worker or touching media.
set -euo pipefail
cd "$(dirname "$0")/.."
env_image="${1:-viddyoop:test}"
env_root="$(mktemp -d "${TMPDIR:-/tmp}/viddyoop-env-check.XXXXXX")"
trap 'rm -rf "$env_root"' EXIT
chmod 0755 "$env_root"
cat > "$env_root/java" <<'SH'
#!/bin/sh
printf '%s' "$VIDDYOOP_TEMPORAL"
SH
chmod 0755 "$env_root/java"
entrypoint_value() {
    docker run --rm --network none --read-only \
      --mount "type=bind,src=$env_root,dst=/probe,readonly" \
      --env PATH=/probe:/usr/bin:/bin "$@" "$env_image" health
}
test "$(entrypoint_value)" = temporal:7233
test "$(entrypoint_value --env VIDYOOP_TEMPORAL=legacy:7233)" = legacy:7233
test "$(entrypoint_value --env VIDYOOP_TEMPORAL=legacy:7233 --env VIDDYOOP_TEMPORAL=current:7233)" = current:7233
python3 - "$env_image" "$env_root" <<'PY'
import json, os, pathlib, subprocess, sys
image, root = sys.argv[1], pathlib.Path(sys.argv[2])
(root / 'password').write_text('unused-prefix-fixture\n')
clean = {k: v for k, v in os.environ.items() if not k.startswith(('VIDDYOOP_', 'VIDYOOP_'))}
values = dict(VIDDYOOP_IMAGE=image, VIDDYOOP_NAS_SUBPATH='isolated',
              VIDDYOOP_NAS_VOLUME='prefix-check-media',
              VIDDYOOP_POSTGRES_PASSWORD_FILE=str(root / 'password'),
              VIDDYOOP_PROFILE='apple-tv-hevc-v1')
command = ['docker', 'compose', '--env-file', '/dev/null', '-p', 'prefix-config-check',
           '-f', 'docker-compose.server.yml', '-f', 'deploy/compose.nas.yml',
           '--profile', 'itunes-import', 'config', '--format', 'json']
def render(settings):
    return json.loads(subprocess.check_output(command, env={**clean, **settings}, text=True))
canonical = render(values)
# Compose 2.38 eagerly expands nested :? checks even when the outer value is set.
# Required settings use invalid references/absolute subpaths instead of allowing
# a missing setting to expose the volume root or initialize replacement secrets.
missing = render({})
assert missing['services']['nas-check']['image'] == 'invalid://set-viddyoop-image'
assert missing['services']['pipeline']['volumes'][0]['volume']['subpath'] == '/__SET_VIDDYOOP_NAS_SUBPATH__'
assert missing['secrets']['postgres-password']['file'] == '/__SET_VIDDYOOP_POSTGRES_PASSWORD_FILE__'
legacy_values = {k.replace('VIDDYOOP_', 'VIDYOOP_', 1): v for k, v in values.items()}
assert render(legacy_values) == canonical, 'Legacy .env values resolve differently'
assert render({**legacy_values, 'VIDYOOP_IMAGE': 'unused:legacy', **values}) == canonical
empty = render({**values, 'VIDYOOP_ITUNES_HOST_MEDIA_ROOT': '/must-not-use',
                'VIDDYOOP_ITUNES_HOST_MEDIA_ROOT': ''})
assert empty['services']['itunes-import']['environment']['VIDDYOOP_ITUNES_HOST_MEDIA_ROOT'] == ''
pipeline = canonical['services']['pipeline']
assert all(not k.startswith('VIDYOOP_') for k in pipeline['environment'])
assert pipeline['environment']['VIDDYOOP_WORK'] == '/data/work'
config = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image], text=True))[0]['Config']
assert config['Labels']['io.viddyoop.env-prefix'] == 'VIDDYOOP_'
assert config['Healthcheck']['Test'] == ['CMD', '/usr/local/bin/viddyoop', 'health']

# Migration preserves values, refuses conflicting keys and never rotates secrets.
script = pathlib.Path('scripts/deploy.py').resolve()
env_file = root / 'old.env'
original = 'VIDYOOP_IMAGE=sha256:unchanged\nVIDYOOP_POSTGRES_PASSWORD_FILE="/private/original"\nOTHER=value\n'
env_file.write_text(original)
subprocess.run([sys.executable, str(script), '--env-file', str(env_file), 'migrate-env'], check=True)
assert env_file.read_text() == original.replace('VIDYOOP_', 'VIDDYOOP_')
backup = env_file.with_name(env_file.name + '.before-viddyoop-prefix')
assert backup.read_text() == original and backup.stat().st_mode & 0o777 == 0o600
subprocess.run([sys.executable, str(script), '--env-file', str(env_file), 'migrate-env'], check=True)
conflict = root / 'conflict.env'
text = 'VIDYOOP_IMAGE=old\nVIDDYOOP_IMAGE=new\n'
conflict.write_text(text)
p = subprocess.run([sys.executable, str(script), '--env-file', str(conflict), 'migrate-env'], capture_output=True)
assert p.returncode != 0 and conflict.read_text() == text
assert not conflict.with_name(conflict.name + '.before-viddyoop-prefix').exists()
print('Canonical/legacy Compose values, image defaults, precedence and configuration migration passed.')
PY
