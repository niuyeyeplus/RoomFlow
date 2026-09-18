#!/usr/bin/env bash
#
# RoomFlow PRODUCTION database restore — runs ON THE HOST.
#
# Replays a gzip'ed mysqldump produced by backup.sh into the production
# database (or, for restore verification, into a scratch schema — see
# TARGET_DB below). The dump contains schema objects and rows but no CREATE
# DATABASE/USE, so the TARGET is chosen here, not baked into the file.
#
# THIS IS A DESTRUCTIVE OPERATION. Tables in the target database are dropped
# and recreated from the dump. To prevent accidents it refuses to run unless
# the caller passes the explicit confirmation flag AND the target database
# name back:
#
#   bash restore.sh <deploy_dir> <backup_file> <confirm_db_name> [env_file_override]
#     e.g. bash restore.sh /opt/roomflow/production \
#            /opt/roomflow/backups/production/roomflow-prod-20260918T100000Z.sql.gz \
#            roomflow
#
# <confirm_db_name> must equal the resolved target database — making the
# operator type it proves the restore is deliberate and aims at the right DB.
#
# Environment overrides (testing only):
#   MYSQL_CONTAINER  default roomflow-prod-mysql
#   TARGET_DB        restore into this database instead of DB_NAME from the
#                    env file — used to verify backups into a scratch schema.
#                    Still requires <confirm_db_name> to match it.
#   SKIP_BACKEND_STOP=yes   do not stop/start the backend around the restore
#                    (use ONLY for scratch-schema verification, never for a
#                    real production restore)
#
# For a REAL production restore the script stops the backend container before
# replaying the dump and starts it again afterwards, so the application is not
# writing rows while its tables are being replaced. The operator is
# responsible for the post-restore health check (docker/health-check.sh or
# the workflow's rollback path).
#
# CREDENTIAL HANDLING: identical to backup.sh — the root password expands
# INSIDE the container from its own environment and never appears on any
# command line. No set -x.
#
# EXIT STATUS:
#   0  restore completed (backend restarted unless SKIP_BACKEND_STOP=yes)
#   1  usage / configuration / confirmation / validation error
#   2  mysql replay failed (a pre-restore safety dump was taken first)
#   3  no running database container
set -euo pipefail

DEPLOY_DIR_ARG="${1-}"
BACKUP_FILE="${2-}"
CONFIRM_DB="${3-}"
ENV_FILE_OVERRIDE="${4-}"

if [ -z "${DEPLOY_DIR_ARG}" ] || [ -z "${BACKUP_FILE}" ]; then
  echo "ERROR: usage: bash restore.sh <deploy_dir> <backup_file.sql.gz> <confirm_db_name> [env_file_override]" >&2
  exit 1
fi

if ! . "${DEPLOY_DIR_ARG}/prod-deploy-lib.sh"; then
  echo "ERROR: cannot load the deploy library from '${DEPLOY_DIR_ARG}/prod-deploy-lib.sh'." >&2
  echo "       It is shipped by the deploy workflow into the deploy dir." >&2
  exit 1
fi

if ! prod_context "${DEPLOY_DIR_ARG}" "${ENV_FILE_OVERRIDE}"; then
  exit 1
fi

# --- resolve the target -----------------------------------------------------
DB_NAME="$(env_value_unquoted "$(env_file_value "${ENV_FILE}" DB_NAME)")"
DB_NAME="${DB_NAME:-roomflow}"
TARGET_DB="${TARGET_DB:-${DB_NAME}}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-roomflow-prod-mysql}"
SKIP_BACKEND_STOP="${SKIP_BACKEND_STOP:-no}"

for dbval in "${TARGET_DB}"; do
  case "${dbval}" in
    '' | *[!A-Za-z0-9_]*)
      echo "ERROR: target database name must match [A-Za-z0-9_]+ (a MySQL identifier)." >&2
      exit 1
      ;;
  esac
done
case "${MYSQL_CONTAINER}" in
  '' | *[!A-Za-z0-9._-]*)
    echo "ERROR: MYSQL_CONTAINER must match [A-Za-z0-9._-]+." >&2
    exit 1
    ;;
esac

# --- the confirmation gate ---------------------------------------------------
if [ "${CONFIRM_DB}" != "${TARGET_DB}" ]; then
  {
    echo "REFUSING to restore."
    echo "  The third argument is the confirmation: it must repeat the target"
    echo "  database name exactly (resolved target: '${TARGET_DB}')."
    echo "  This proves the restore is deliberate and aimed at the right DB."
    echo "  Re-run as:"
    echo "    bash restore.sh ${DEPLOY_DIR_ARG} ${BACKUP_FILE} ${TARGET_DB}"
  } >&2
  exit 1
fi

# --- validate the backup file ------------------------------------------------
# Allowlist the path characters (absolute path, same rule as the deploy lib),
# require it to be a real file, require the .sql.gz suffix, and prove it is
# valid gzip before touching the database.
case "${BACKUP_FILE}" in
  /*)
    case "${BACKUP_FILE}" in
      *[!A-Za-z0-9._/-]* | *..*)
        echo "ERROR: backup file path uses characters outside [A-Za-z0-9._/-] or contains '..'." >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "ERROR: backup file must be an absolute path (e.g. ${BACKUP_DIR:-/opt/roomflow/backups/production}/roomflow-prod-<ts>.sql.gz)." >&2
    exit 1
    ;;
esac
case "${BACKUP_FILE}" in
  *.sql.gz) : ;;
  *)
    echo "ERROR: backup file must end in .sql.gz (the format backup.sh writes)." >&2
    exit 1
    ;;
esac
if [ ! -f "${BACKUP_FILE}" ]; then
  echo "ERROR: backup file not found: ${BACKUP_FILE}" >&2
  exit 1
fi
if ! gzip -t "${BACKUP_FILE}" 2>/dev/null; then
  echo "ERROR: ${BACKUP_FILE} is not valid gzip." >&2
  exit 1
fi

# --- is the database running? -------------------------------------------------
# Same disambiguation as backup.sh: `docker inspect` fails for a missing
# container AND for an unreachable daemon; a restore must fail closed either
# way, but the message should say which happened.
if ! RUNNING="$(docker inspect --format '{{.State.Running}}' "${MYSQL_CONTAINER}" 2>/dev/null)"; then
  if docker info >/dev/null 2>&1; then
    echo "ERROR: database container '${MYSQL_CONTAINER}' does not exist; cannot restore." >&2
  else
    echo "ERROR: cannot inspect '${MYSQL_CONTAINER}' and the docker daemon is unreachable (or the caller lacks docker permission)." >&2
  fi
  exit 3
fi
if [ "${RUNNING}" != "true" ]; then
  echo "ERROR: database container '${MYSQL_CONTAINER}' exists but is not running; cannot restore." >&2
  exit 3
fi

# --- pre-restore safety dump --------------------------------------------------
# Only when restoring over the REAL database (not a scratch verification):
# take one more backup of whatever is currently there, so a bad restore is
# itself reversible. It lands in BACKUP_DIR like every other dump.
if [ "${TARGET_DB}" = "${DB_NAME}" ]; then
  echo "Taking a pre-restore safety dump of the current '${DB_NAME}' first..."
  # MYSQL_CONTAINER is passed explicitly: it is a plain (unexported) variable,
  # so without this the child backup.sh would dump the DEFAULT container while
  # we restore into the overridden one.
  if ! MYSQL_CONTAINER="${MYSQL_CONTAINER}" bash "${DEPLOY_DIR}/backup.sh" "${DEPLOY_DIR}" "${ENV_FILE_OVERRIDE}"; then
    echo "ERROR: pre-restore safety dump failed; refusing to proceed." >&2
    exit 2
  fi
fi

# --- stop the backend so nothing writes during the restore -------------------
BACKEND_CONTAINER="${MYSQL_CONTAINER%-mysql}-backend"
BACKEND_WAS_RUNNING='no'
# If the script dies by signal between `docker stop` and the restart, the
# backend must not be left down: the EXIT trap restarts it whenever it was
# running and has not been restarted yet. Handled error paths clear the flag
# first so the trap is a no-op on the normal exits.
restore_backend_on_exit() {
  if [ "${BACKEND_WAS_RUNNING}" = 'yes' ]; then
    echo "restore.sh: caught an exit while the backend was stopped - restarting '${BACKEND_CONTAINER}'." >&2
    docker start "${BACKEND_CONTAINER}" >/dev/null 2>&1 || \
      echo "restore.sh: WARNING - could not restart '${BACKEND_CONTAINER}'; start it manually." >&2
  fi
}
trap restore_backend_on_exit EXIT

if [ "${SKIP_BACKEND_STOP}" != "yes" ]; then
  BR="$(docker inspect --format '{{.State.Running}}' "${BACKEND_CONTAINER}" 2>/dev/null || true)"
  if [ "${BR}" = "true" ]; then
    echo "Stopping backend container '${BACKEND_CONTAINER}' for the restore..."
    docker stop "${BACKEND_CONTAINER}" >/dev/null
    BACKEND_WAS_RUNNING='yes'
  fi
fi

# --- create target if needed, then replay -------------------------------------
# Create the target schema inside the container (password from container env).
if ! docker exec "${MYSQL_CONTAINER}" sh -c \
    'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS \`$1\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"' \
    _ "${TARGET_DB}"; then
  echo "ERROR: could not create target database '${TARGET_DB}'." >&2
  if [ "${BACKEND_WAS_RUNNING}" = 'yes' ]; then
    docker start "${BACKEND_CONTAINER}" >/dev/null || true
    BACKEND_WAS_RUNNING='no'
  fi
  exit 2
fi

echo "Replaying ${BACKUP_FILE} into database '${TARGET_DB}' on ${MYSQL_CONTAINER}..."
if ! gzip -dc "${BACKUP_FILE}" | docker exec -i "${MYSQL_CONTAINER}" sh -c \
    'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$1"' \
    _ "${TARGET_DB}"; then
  echo "ERROR: mysql replay FAILED partway; '${TARGET_DB}' may be partially restored." >&2
  echo "       The pre-restore safety dump (if taken) is in BACKUP_DIR." >&2
  if [ "${BACKEND_WAS_RUNNING}" = 'yes' ]; then
    docker start "${BACKEND_CONTAINER}" >/dev/null || true
    BACKEND_WAS_RUNNING='no'
  fi
  exit 2
fi

if [ "${BACKEND_WAS_RUNNING}" = 'yes' ]; then
  echo "Starting backend container '${BACKEND_CONTAINER}'..."
  docker start "${BACKEND_CONTAINER}" >/dev/null
  BACKEND_WAS_RUNNING='no'
fi

echo "Restore into '${TARGET_DB}' complete."
exit 0
