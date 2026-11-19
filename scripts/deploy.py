#!/usr/bin/env python3
"""Operate the isolated NAS/PostgreSQL deployment without changing Docker settings."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
DOCKER_APP = Path('/Applications/Docker.app/Contents/Resources')


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
    print(f'Created {env_file}; set VIDYOOP_IMAGE and verify NAS volume/subpath before up.')


def docker_command(env_file, project):
    candidate = os.environ.get('VIDYOOP_DOCKER_BIN') or shutil.which('docker')
    if not candidate and (DOCKER_APP / 'bin/docker').exists():
        candidate = str(DOCKER_APP / 'bin/docker')
    if not candidate:
        raise ValueError('Docker CLI is unavailable; set VIDYOOP_DOCKER_BIN.')
    docker = str(Path(candidate).resolve())
    env = os.environ.copy()
    env['COMPOSE_PROFILES'] = ''  # Automatic host-app integration stays opt-in.
    # A private anonymous-pull configuration avoids the interactive macOS login
    # Keychain. Never rewrite the user's Docker config or restart Docker Desktop.
    config_dir = Path(os.environ.get('VIDYOOP_DOCKER_CONFIG', str(env_file.parent / '.docker-cli'))).expanduser().resolve()
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'local/macstudio.env')
    parser.add_argument('--project', default='viddyoop-macstudio')
    parser.add_argument('command', choices=['init', 'config', 'check', 'up', 'status', 'logs', 'stop', 'compose'])
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
    compose, env = docker_command(env_file, args.project)
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
