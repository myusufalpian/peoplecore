#!/usr/bin/env bash
set -euo pipefail

: "${PGHOST:=127.0.0.1}"
: "${PGPORT:=54329}"
: "${PGDATABASE:=peoplecore}"
: "${PGUSER:=peoplecore}"
: "${PGPASSWORD:?Set PGPASSWORD before running backup-restore}"

ACTION="${1:-backup}"
ARCHIVE_PATH="${2:-./build/peoplecore-backup.dump}"

case "${ACTION}" in
  backup)
    mkdir -p "$(dirname "${ARCHIVE_PATH}")"
    pg_dump --format=custom --no-owner --no-privileges \
      --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
      --dbname="${PGDATABASE}" --file="${ARCHIVE_PATH}"
    pg_restore --list "${ARCHIVE_PATH}" >/dev/null
    ;;
  restore)
    if [[ "${PEOPLECORE_RECOVERY_ISOLATED:-false}" != "true" ]]; then
      echo "PEOPLECORE_RECOVERY_ISOLATED=true is required before restoring" >&2
      exit 1
    fi
    pg_restore --clean --if-exists --no-owner --no-privileges --single-transaction \
      --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
      --dbname="${PGDATABASE}" "${ARCHIVE_PATH}"
    ;;
  *)
    echo "Usage: $0 <backup|restore> [archive-path]" >&2
    exit 1
    ;;
esac
