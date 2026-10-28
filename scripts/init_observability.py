#!/usr/bin/env python3
"""Prepare private local monitoring state without printing or rotating credentials."""
import os
from pathlib import Path
import secrets
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def private_write(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix='.credential-', dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(value)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def initialize(root):
    envfile = root / '.env'
    if not envfile.exists():
        raise ValueError('Run scripts/init_env.py first; no credentials were changed.')
    text = envfile.read_text()
    values = {}
    for line in text.splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, separator, value = line.partition('=')
            if separator:
                values[key.strip()] = value.strip()
    key = os.environ.get('APP_API_KEY', values.get('APP_API_KEY', ''))
    if len(key) < 32 or '\n' in key or '\r' in key:
        raise ValueError('APP_API_KEY must be a generated secret; no secrets were printed.')
    identity = {'OBSERVABILITY_UID': str(os.getuid()), 'OBSERVABILITY_GID': str(os.getgid())}
    for name, value in identity.items():
        if name in values and values[name] != value:
            raise ValueError(f'{name} belongs to a different host user; review the local .env value.')
    addition = ''.join(f'{name}={value}\n' for name, value in identity.items() if name not in values)
    if addition:
        private_write(envfile, text.rstrip('\n') + '\n' + addition)
    os.chmod(envfile, 0o600)
    local = root / '.local' / 'observability'
    if local.is_symlink():
        raise ValueError('The local observability directory must not be a symlink.')
    local.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(local, 0o700)
    for name in ['prometheus-data', 'grafana-data']:
        directory = local / name
        if directory.is_symlink():
            raise ValueError('Monitoring state directories must not be symlinks.')
        directory.mkdir(exist_ok=True, mode=0o700)
        os.chmod(directory, 0o700)
    private_write(local / 'app-api-key', key)
    password = local / 'grafana-admin-password'
    if password.is_symlink():
        raise ValueError('The local Grafana credential must not be a symlink.')
    if not password.exists():
        private_write(password, secrets.token_urlsafe(32))
    os.chmod(password, 0o600)
    return local


def main():
    try:
        path = initialize(ROOT)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print(f'Prepared private local monitoring state at {path}. Credentials not displayed.')


if __name__ == '__main__':
    main()
