#!/usr/bin/env bash
# Builds and runs everything in Docker: Postgres and two instances of the scheduler.
# Open http://localhost:8080 (the second instance is on 8081). Ctrl-C stops it.
set -euo pipefail
cd "$(dirname "$0")"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

docker compose up --build
