#!/usr/bin/env bash
#
# RoomFlow PRODUCTION database backup — runs ON THE HOST.
#
# Dumps the production MySQL database INSIDE the mysql container (mysqldump
# ships in the mysql:8.4 image) and writes a gzip'ed dump into BACKUP_DIR.
# The deploy workflow (deploy-production.yml) runs this BEFORE every deploy,
# so a failed release always has a pre-deploy restore point; it can also be
# run by hand at any time.
#
# Usage (from the deploy directory, or anywhere — paths come from arguments):
#   bash backup.sh <deploy_dir> [env_file_override]
#     e.g. bash backup.sh /opt/roomflow/production
#
# Environment overrides (rarely needed):
#   MYSQL_CONTAINER  default roomflow-prod-mysql (override ONLY for testing
#                    the script against another stack's container)
#
# WHAT IT READS FROM THE ENV FILE (names only — values are never printed):
#   DB_NAME          default roomflow
#   BACKUP_DIR       default /opt/roomflow/backups/production
#   BACKUP_KEEP      default 10 (newest dumps retained, older pruned)
#
# CREDENTIAL HANDLING — the root password NEVER crosses a command line:
#   mysqldump runs inside the container and expands $MYSQL_ROOT_PASSWORD from
#   the CONTAINER's own environment (compose sets it there), so the secret is
#   not an argument to docker exec, is not on the remote ssh command line, and
#   cannot appear in `ps` output on the host. `set -x` is deliberately not
#   used anywhere in this script.
#
# EXIT STATUS (the deploy workflow maps these):
#   0  backup written and verified
#   1  usage / configuration error (bad paths, bad values, library missing)
#   2  mysqldump or gzip verification failed
#   3  no running database to back up (first deploy — nothing exists yet)
set -euo pipefail

DEPLOY_DIR_ARG="${1-}"
ENV_FILE_OVERRIDE="${2-}"

if [ -z "${DEPLOY_DIR_ARG}" ]; then
  echo "ERROR: usage: bash backup.sh <deploy_dir> [env_file_override]" >&2
  exit 1
fi

# Source first: `.` with a QUOTED path is not shell syntax, so an unusable
# deploy dir fails to source rather than executing anything. The allowlist
# check inside prod_context follows immediately.
if ! . "${DEPLOY_DIR_ARG}/prod-deploy-lib.sh"; then
  echo "ERROR: cannot load the deploy library from '${DEPLOY_DIR_ARG}/prod-deploy-lib.sh'." >&2
  echo "       It is shipped by the deploy workflow into the deploy dir." >&2
  exit 1
fi

if ! prod_context "${DEPLOY_DIR_ARG}" "${ENV_FILE_OVERRIDE}"; then
  exit 1
fi

# --- settings from the operator's env file (names only, never logged) -------
DB_NAME="$(env_value_unquoted "$(env_file_value "${ENV_FILE}" DB_NAME)")"
DB_NAME="${DB_NAME:-roomflow}"
BACKUP_DIR="$(env_value_unquoted "$(env_file_value "${ENV_FILE}" BACKUP_DIR)")"
BACKUP_DIR="${BACKUP_DIR:-/opt/roomflow/backups/production}"
BACKUP_KEEP="$(env_value_unquoted "$(env_file_value "${ENV_FILE}" BACKUP_KEEP)")"
BACKUP_KEEP="${BACKUP_KEEP:-10}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-roomflow-prod-mysql}"

# Every value that reaches a shell command line is re-proven here, on the
# host, with the same newline-safe rules as the deploy library.
case "${DB_NAME}" in
  '' | *[!A-Za-z0-9_]*)
    echo "ERROR: DB_NAME in ${ENV_FILE} must match [A-Za-z0-9_]+ (a MySQL identifier). Refusing to build a command from it." >&2
    exit 1
    ;;
esac
if ! validate_deploy_dir "${BACKUP_DIR}"; then
  echo "ERROR: BACKUP_DIR must be an absolute path using only [A-Za-z0-9._/-]." >&2
  exit 1
fi
case "${BACKUP_KEEP}" in
  '' | *[!0-9]* | 0 | 0[0-9]*)
    echo "ERROR: BACKUP_KEEP must be a positive integer (got a non-numeric or zero value)." >&2
    exit 1
    ;;
esac
case "${MYSQL_CONTAINER}" in
  '' | *[!A-Za-z0-9._-]*)
    echo "ERROR: MYSQL_CONTAINER must match [A-Za-z0-9._-]+." >&2
    exit 1
    ;;
esac

# --- is there a running database? -------------------------------------------
RUNNING="$(docker inspect --format '{{.State.Running}}' "${MYSQL_CONTAINER}" 2>/dev/null || true)"
if [ "${RUNNING}" != "true" ]; then
  echo "No running database container '${MYSQL_CONTAINER}' — nothing to back up (expected on a first deploy)."
  exit 3
fi

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${BACKUP_DIR}/roomflow-prod-${TS}.sql.gz"

# --single-transaction: consistent InnoDB snapshot without locking writes.
# --routines/--triggers: complete schema objects. The dump deliberately does
# NOT use --databases: it contains only schema objects and rows for DB_NAME,
# so restore.sh can direct it at the production database OR at a scratch
# schema for restore verification without editing the file.
# --set-gtid-purged=OFF: keeps the dump replayable on a plain instance.
# The password expands INSIDE the container from its own env — see the header.
# DB_NAME travels as "$1" of the in-container sh, never interpolated into the
# command string by the host shell.
if ! docker exec "${MYSQL_CONTAINER}" sh -c \
    'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF "$1"' \
    _ "${DB_NAME}" \
    | gzip > "${OUT}"; then
  rm -f "${OUT}"
  echo "ERROR: mysqldump failed inside ${MYSQL_CONTAINER}; no backup written." >&2
  exit 2
fi

# pipefail above already catches a mysqldump failure; verify what landed.
if [ ! -s "${OUT}" ] || ! gzip -t "${OUT}" 2>/dev/null; then
  rm -f "${OUT}"
  echo "ERROR: backup file ${OUT} is empty or not valid gzip." >&2
  exit 2
fi

chmod 600 "${OUT}"
echo "Backup written: ${OUT} ($(du -h "${OUT}" | cut -f1))"

# --- retention: keep the newest BACKUP_KEEP dumps ----------------------------
# ls -1t is safe here: the script itself names every file it creates with a
# fixed prefix and timestamp, and the glob is unexpanded-safe (nullglob).
shopt -s nullglob
dumps=( "${BACKUP_DIR}"/roomflow-prod-*.sql.gz )
if [ "${#dumps[@]}" -gt "${BACKUP_KEEP}" ]; then
  # Sort newest-first by name (timestamped names sort chronologically).
  mapfile -t dumps < <(printf '%s\n' "${dumps[@]}" | sort -r)
  for old in "${dumps[@]:${BACKUP_KEEP}}"; do
    rm -f "${old}"
    echo "Pruned old backup: ${old}"
  done
fi

exit 0
