#!/bin/sh
set -e
DATA_DIR="$(dirname "${DB_PATH:-/data/push.db}")"
mkdir -p "$DATA_DIR"
if [ "$(id -u)" = "0" ]; then
  chown -R push:push "$DATA_DIR"
  exec su-exec push "$@"
fi
exec "$@"
