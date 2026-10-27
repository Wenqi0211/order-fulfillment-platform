#!/usr/bin/env bash
# Keep this project's Docker client configuration separate from macOS Keychain helpers.
# Only a local Unix-socket Docker engine is supported by this development wrapper.
set -euo pipefail
umask 077
project_root="$(cd "$(dirname "$0")/.." && pwd)"
project_docker_config="$project_root/.tooling/docker"
mkdir -p "$project_docker_config"
chmod 700 "$project_docker_config"
if [[ ! -f "$project_docker_config/config.json" ]]; then
  # Explicit anonymous Docker Hub entry prevents macOS Docker CLI's automatic
  # credential-helper discovery. Do not copy the user's global credential store.
  python3 - "$project_docker_config/config.json" <<'PY'
import json
import os
from pathlib import Path
import sys
path = Path(sys.argv[1])
config = {"auths": {"https://index.docker.io/v1/": {}}}
plugins = Path('/Applications/Docker.app/Contents/Resources/cli-plugins')
if plugins.is_dir():
    config['cliPluginsExtraDirs'] = [str(plugins)]
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    pass
else:
    with os.fdopen(descriptor, 'w') as output:
        json.dump(config, output, indent=2)
        output.write('\n')
PY
fi
chmod 600 "$project_docker_config/config.json"
export DOCKER_CONFIG="$project_docker_config"
# DOCKER_CONTEXT takes precedence over DOCKER_HOST in the CLI; force this wrapper
# to use the explicitly selected local socket even when a remote context is set.
unset DOCKER_CONTEXT
if [[ -z "${DOCKER_HOST:-}" ]]; then
  if [[ -S "$HOME/.docker/run/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
  elif [[ -n "${XDG_RUNTIME_DIR:-}" && -S "$XDG_RUNTIME_DIR/docker.sock" ]]; then
    export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/docker.sock"
  else
    export DOCKER_HOST='unix:///var/run/docker.sock'
  fi
fi
case "$DOCKER_HOST" in
  unix://*) ;;
  *) echo 'This wrapper supports a local Unix Docker socket only; refusing a remote Docker host.' >&2; exit 2 ;;
esac
if [[ -x /Applications/Docker.app/Contents/Resources/bin/docker ]]; then
  docker_binary='/Applications/Docker.app/Contents/Resources/bin/docker'
else
  docker_binary="$(command -v docker)" || { echo 'Install and start Docker Engine/Desktop first.' >&2; exit 1; }
fi
cd "$project_root"
exec "$docker_binary" "$@"
