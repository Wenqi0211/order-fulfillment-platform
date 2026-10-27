#!/bin/sh
# Maven is isolated inside the project; no global installation or ~/.m2 writes.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_VERSION=3.9.11
TASK_MAVEN="$TASK_ROOT/.tooling/apache-maven-$TASK_VERSION/bin/mvn"
if [ ! -x "$TASK_MAVEN" ]; then
  mkdir -p "$TASK_ROOT/.tooling"
  TASK_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$TASK_VERSION/apache-maven-$TASK_VERSION-bin.tar.gz"
  curl --fail --location --silent --show-error "$TASK_URL" -o "$TASK_ROOT/.tooling/maven.tar.gz"
  curl --fail --silent --show-error "$TASK_URL.sha512" -o "$TASK_ROOT/.tooling/maven.sha512"
  python3 - "$TASK_ROOT" <<'PY'
import hashlib, pathlib, sys, tarfile
p = pathlib.Path(sys.argv[1]) / '.tooling'
a = p / 'maven.tar.gz'
if hashlib.sha512(a.read_bytes()).hexdigest() != (p / 'maven.sha512').read_text().strip().split()[0]:
    raise SystemExit('Maven distribution checksum mismatch')
with tarfile.open(a) as t:
    if any(m.name.startswith('/') or '..' in pathlib.PurePosixPath(m.name).parts for m in t.getmembers()):
        raise SystemExit('Unsafe archive path')
    t.extractall(p, filter='data')
PY
fi
exec "$TASK_MAVEN" "-Dmaven.repo.local=$TASK_ROOT/.tooling/m2" "$@"
