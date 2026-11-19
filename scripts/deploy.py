#!/usr/bin/env python3
"""Operate the isolated NAS/PostgreSQL deployment without changing Docker settings."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
DOCKER_APP = Path('/Applications/Docker.app/Contents/Resources')


def environment_value(name, fallback=None):
    value = os.environ.get(name)
    if value is None:
        value = os.environ.get(name.replace('VIDDYOOP_', 'VIDYOOP_', 1))
    return fallback if value is None else value


def migrate_environment(env_file):
    """Rename only keys; preserve values and keep a private original copy."""
    text = env_file.read_text()
    pattern = re.compile(r'^(\s*(?:export\s+)?)(VIDD?YOOP_[A-Z0-9_]+)(\s*=.*)$')
    existing = {}
    for line in text.splitlines():
        match = pattern.match(line)
        if match and match[2].startswith('VIDDYOOP_'):
            existing[match[2]] = match[3].strip()
    result = []
    renamed = 0
    for line in text.splitlines(keepends=True):
        match = pattern.match(line.rstrip('\r\n'))
        if match and match[2].startswith('VIDYOOP_'):
            key = match[2].replace('VIDYOOP_', 'VIDDYOOP_', 1)
            if key in existing:
                if existing[key] != match[3].strip():
                    raise ValueError(f'Conflicting canonical and legacy values for {key}; file unchanged.')
                renamed += 1
                continue
            line = line.replace(match[2], key, 1)
            renamed += 1
        result.append(line)
    if not renamed:
        print('Environment keys already use VIDDYOOP_.')
        return
    backup = env_file.with_name(env_file.name + '.before-viddyoop-prefix')
    fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())
    with tempfile.NamedTemporaryFile(mode='w', dir=env_file.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(''.join(result))
            stream.flush()
            os.fsync(stream.fileno())
            temporary.chmod(env_file.stat().st_mode & 0o777)
            temporary.replace(env_file)
        finally:
            temporary.unlink(missing_ok=True)
    print(f'Renamed {renamed} keys; original configuration retained at {backup}.')


def initialize(env_file):
    """Keep existing configuration and credentials; never rotate a live password."""
    if env_file.exists():
        raise ValueError(f'Configuration already exists: {env_file}')
    env_file.parent.mkdir(parents=True, exist_ok=True)
    secret_dir = env_file.parent / 'secrets'
    secret_dir.mkdir(mode=0o700, exist_ok=True)
    secret_dir.chmod(0o700)
    secret_file = secret_dir / 'postgres-password'
    if not secret_file.exists():
        fd = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
        with os.fdopen(fd, 'w') as stream:
            stream.write(secrets.token_hex(32) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        # Readable in containers; access on the host is restricted by the parent.
        secret_file.chmod(0o444)
    text = (ROOT / 'deploy/macstudio.env.example').read_text()
    text = text.replace('/absolute/private/postgres-password', json.dumps(str(secret_file)))
    fd = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(text)
    print(f'Created {env_file}; set VIDDYOOP_IMAGE and verify NAS volume/subpath before up.')


def docker_command(env_file, project):
    candidate = environment_value('VIDDYOOP_DOCKER_BIN') or shutil.which('docker')
    if not candidate and (DOCKER_APP / 'bin/docker').exists():
        candidate = str(DOCKER_APP / 'bin/docker')
    if not candidate:
        raise ValueError('Docker CLI is unavailable; set VIDDYOOP_DOCKER_BIN.')
    docker = str(Path(candidate).resolve())
    env = os.environ.copy()
    env['COMPOSE_PROFILES'] = ''  # Automatic host-app integration stays opt-in.
    # A private anonymous-pull configuration avoids the interactive macOS login
    # Keychain. Never rewrite the user's Docker config or restart Docker Desktop.
    config_dir = Path(environment_value('VIDDYOOP_DOCKER_CONFIG', str(env_file.parent / '.docker-cli'))).expanduser().resolve()
    config_dir.mkdir(mode=0o700, exist_ok=True)
    config_file = config_dir / 'config.json'
    if not config_file.exists():
        config = {}
        if (DOCKER_APP / 'cli-plugins').exists():
            config['cliPluginsExtraDirs'] = [str(DOCKER_APP / 'cli-plugins')]
        config_file.write_text(json.dumps(config) + '\n')
    env['DOCKER_CONFIG'] = str(config_dir)
    env['PATH'] = os.defpath  # Avoid automatic Keychain-helper discovery.
    env.pop('DOCKER_CONTEXT', None)
    desktop_socket = Path.home() / '.docker/run/docker.sock'
    if 'DOCKER_HOST' not in env and desktop_socket.exists():
        env['DOCKER_HOST'] = 'unix://' + str(desktop_socket)
    compose = [docker, 'compose', '--env-file', str(env_file), '-p', project,
               '-f', str(ROOT / 'docker-compose.server.yml'),
               '-f', str(ROOT / 'deploy/compose.nas.yml')]
    return compose, env


def require_canonical_image(compose, env):
    config = subprocess.run(compose + ['config', '--format', 'json'], env=env, capture_output=True, text=True)
    if config.returncode:
        raise ValueError(config.stderr.strip())
    image = json.loads(config.stdout)['services']['pipeline']['image']
    inspect = [compose[0], 'image', 'inspect', image, '--format',
               '{{index .Config.Labels "io.viddyoop.env-prefix"}}']
    result = subprocess.run(inspect, env=env, capture_output=True, text=True)
    if result.returncode:
        if subprocess.run([compose[0], 'pull', image], env=env).returncode:
            raise ValueError('Unable to pull the selected deployment image.')
        result = subprocess.run(inspect, env=env, capture_output=True, text=True)
    if result.returncode or result.stdout.strip() != 'VIDDYOOP_':
        raise ValueError('Selected image predates VIDDYOOP_ support; pin the corrected release before up. '
                         'The running deployment was not changed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'local/macstudio.env')
    parser.add_argument('--project', default='viddyoop-macstudio')
    parser.add_argument('command', choices=['init', 'migrate-env', 'config', 'check', 'up', 'status', 'logs', 'stop', 'compose'])
    parser.add_argument('compose_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.compose_args and args.command != 'compose':
        parser.error('Extra Compose arguments require the compose command.')
    env_file = args.env_file.expanduser().resolve()
    if args.command == 'init':
        initialize(env_file)
        return 0
    if not env_file.is_file():
        raise ValueError(f'Missing configuration: {env_file}; use init or --env-file.')
    if args.command == 'migrate-env':
        migrate_environment(env_file)
        return 0
    compose, env = docker_command(env_file, args.project)
    if args.command == 'up' or (args.command == 'compose' and args.compose_args[:1] == ['up']):
        require_canonical_image(compose, env)
    if args.command == 'compose':
        return subprocess.run(compose + args.compose_args, env=env).returncode
    commands = {
        'config': ['config'],
        'check': ['run', '--rm', '--no-deps', 'nas-check'],
        'up': ['up', '-d', '--no-build', '--wait', '--wait-timeout', '180'],
        'status': ['exec', '-T', 'pipeline', 'java', '-jar', '/app/viddyoop.jar', 'status', '--json'],
        'logs': ['logs', '--no-color', '--tail', '100', 'pipeline'],
        'stop': ['stop'],
    }
    return subprocess.run(compose + commands[args.command], env=env).returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
