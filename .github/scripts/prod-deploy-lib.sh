# Production overlay for the shared deploy library
# (.github/scripts/staging-deploy-lib.sh).
#
# WHY A THIN OVERLAY AND NOT A COPY: the staging library's header documents the
# bug class this layout exists to prevent - deploy, health-check, rollback and
# diagnostics each carrying their own copy of the validation/env-file rules
# that then DIVERGED. Copying those ~480 lines into a second library would
# recreate exactly that hazard: two editable copies that can drift apart.
# Instead this file SOURCES the staging library and overrides only the four
# functions whose contract is genuinely environment-specific:
#
#   resolve_env_file     candidate names (.env.production first, then .env)
#   env_file_help        the actionable message text
#   resolve_probe_url    PROD_* port variable names and defaults
#   prod_context         the one resolution entry point (COMPOSE_FILE name)
#
# Everything else - the newline-safe validators, the env-file reader, the
# single-quote/dollar-sign rules, is_bcrypt_hash, require_env_vars, url_port -
# is environment-agnostic and is inherited unchanged, so the runner and the
# production host cannot disagree about what is acceptable, and a fix to a
# shared rule lands on both stacks at once.
#
# CALLING CONVENTION is identical: source this file instead of
# staging-deploy-lib.sh (`. "${DEPLOY_DIR}/prod-deploy-lib.sh`), then call
# prod_context in place of staging_context. The workflow ships BOTH files into
# the deploy directory; this file locates its sibling via BASH_SOURCE so it
# does not depend on the caller's working directory.

# Resolve the directory this file lives in (the deploy dir on the host, or
# .github/scripts on the runner) and load the shared library from there.
_PROD_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=/dev/null
# `|| return 1` matters: this file is itself sourced inside callers' `if !`
# context, where a failed inner `.` would otherwise be swallowed and the outer
# source would still succeed (the last statement is a function definition),
# surfacing later as a misleading `command not found` instead of "deploy
# library missing".
. "${_PROD_LIB_DIR}/staging-deploy-lib.sh" || return 1
unset _PROD_LIB_DIR

# ---------------------------------------------------------------------------
# Environment-specific overrides (shadow the staging versions after sourcing)
# ---------------------------------------------------------------------------

# resolve_env_file <deploy_dir> <override>
#   Same contract as the staging version: the override is used AS GIVEN and
#   never falls back; auto-detect order is `.env.production` FIRST, then
#   `.env` — `.env.production` is the primary name every production artifact
#   documents (the compose header, .env.production.example, docs).
resolve_env_file() {
  local deploy_dir="$1" override="$2" candidate
  if [ -n "${override}" ]; then
    printf '%s' "${deploy_dir}/${override}"
    return 0
  fi
  for candidate in "${deploy_dir}/.env.production" "${deploy_dir}/.env"; do
    if [ -f "${candidate}" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  return 1
}

# env_file_help <deploy_dir> <override>
#   The actionable message printed whenever no usable env file was found.
env_file_help() {
  local deploy_dir="$1" override="$2"
  {
    echo "ERROR: no usable env file found for the production stack."
    echo "       Looked for ${deploy_dir}/.env.production (the documented primary name),"
    echo "       then ${deploy_dir}/.env."
    echo "       PROD_ENV_FILE override: ${override:-(not set)}"
    echo "       Refusing to continue: without this file Compose interpolates \${VAR}"
    echo "       placeholders to empty strings and the stack comes up broken."
    echo "       Create one from the tracked template, e.g.:"
    echo "         cp .env.production.example ${deploy_dir}/.env.production"
    echo "       then fill in every value it marks as required. The file is"
    echo "       operator-owned: this workflow never creates or overwrites it."
  } >&2
}

# resolve_probe_url <role> <env_file> <explicit_override>
#   Same contract as the staging version, reading the PROD_* port variables
#   with the production defaults (backend 28080, frontend 8082).
resolve_probe_url() {
  local role="$1" env_file="$2" override="$3"
  local port_var url_var default_port file_port port override_port

  case "${role}" in
    backend)
      port_var='PROD_BACKEND_PORT'
      url_var='PROD_BACKEND_URL'
      default_port='28080'
      ;;
    frontend)
      port_var='PROD_FRONTEND_PORT'
      url_var='PROD_FRONTEND_URL'
      default_port='8082'
      ;;
    *)
      echo "ERROR: unknown probe role '${role}'." >&2
      return 1
      ;;
  esac

  file_port="$(env_file_value "${env_file}" "${port_var}")"
  file_port="$(env_value_unquoted "${file_port}")"
  port="${file_port:-${default_port}}"

  case "${port}" in
    '' | *[!0-9]*)
      # Only the NAME is echoed - see the invariant in staging-deploy-lib.sh.
      echo "ERROR: ${port_var} in ${env_file} is not a number." >&2
      return 1
      ;;
  esac
  case "${port}" in
    0[0-9]*)
      echo "ERROR: ${port_var}=${port} in ${env_file} has a leading zero; write the port as a plain decimal number (08080 -> 8080)." >&2
      return 1
      ;;
  esac
  if [ "${#port}" -gt 5 ] || [ "${port}" -lt 1 ] || [ "${port}" -gt 65535 ]; then
    echo "ERROR: ${port_var}=${port} in ${env_file} is out of the valid TCP port range (1-65535)." >&2
    return 1
  fi

  if [ -n "${override}" ]; then
    override_port="$(url_port "${override}")"
    if [ -n "${file_port}" ] && [ -n "${override_port}" ] && [ "${override_port}" != "${port}" ]; then
      {
        echo "ERROR: ${url_var}='${override}' and ${port_var}=${port} in ${env_file} disagree about the port."
        echo "       The stack is published on ${port} (docker-compose.prod.yml maps it from ${port_var}),"
        echo "       so probing ${override} would test something that is not this deploy."
        echo "       Fix one of the two: unset the ${url_var} repository variable to let the port come"
        echo "       from the env file, or set ${port_var} in ${env_file} to ${override_port:-the port in the override}."
      } >&2
      return 1
    fi
    printf '%s' "${override}"
    return 0
  fi

  printf 'http://127.0.0.1:%s' "${port}"
}

# ---------------------------------------------------------------------------
# Deploy context
# ---------------------------------------------------------------------------

# prod_context <deploy_dir> <env_file_override>
#   Mirrors staging_context: validates both arguments and resolves the env
#   file, setting the globals DEPLOY_DIR, COMPOSE_FILE and ENV_FILE. This is
#   the ONE resolution path - deploy, backup, health check, rollback and
#   diagnostics all call it, so they cannot drift apart.
prod_context() {
  local deploy_dir="$1" override="$2" resolved

  if ! validate_deploy_dir "${deploy_dir}"; then
    echo "ERROR: the deploy dir must be an absolute path using only [A-Za-z0-9._/-]." >&2
    return 1
  fi
  if ! validate_env_override "${override}"; then
    echo "ERROR: PROD_ENV_FILE must be a bare file name inside the deploy dir - no '/', no '..', no shell metacharacters." >&2
    return 1
  fi

  DEPLOY_DIR="${deploy_dir}"
  COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.prod.yml"
  if [ -n "${override}" ]; then
    ENV_FILE="${DEPLOY_DIR}/${override}"
  else
    if ! resolved="$(resolve_env_file "${DEPLOY_DIR}" '')"; then
      resolved=''
    fi
    ENV_FILE="${resolved}"
  fi

  if [ -z "${ENV_FILE}" ] || [ ! -f "${ENV_FILE}" ]; then
    env_file_help "${DEPLOY_DIR}" "${override}"
    return 1
  fi
  return 0
}
