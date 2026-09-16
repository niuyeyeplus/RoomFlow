#!/usr/bin/env bash
#
# Shared helpers for the staging deploy workflow
# (.github/workflows/deploy-staging.yml).
#
# WHY THIS FILE EXISTS
#   The deploy, health-check, rollback and diagnostics steps all need the same
#   three things: newline-safe validation of the values they were handed, the
#   env-file resolution rules, and the "is the stack's env file usable"
#   pre-flight. Those used to be copy-pasted into each remote script and had
#   already DIVERGED - only the deploy copy ran the required-variable scan, so a
#   rollback could start against an env file the deploy would have rejected and
#   recreate the very breakage that triggered it. One copy, shipped to the host
#   by the workflow alongside the compose file and health-check.sh, removes that
#   whole class of bug.
#
# HOW IT IS USED
#   The workflow `scp`s this file into the deploy directory on the staging host
#   and every remote script begins with
#       . "${DEPLOY_DIR}/staging-deploy-lib.sh"
#   The runner also sources it (from the checked-out revision) for the same
#   validation, so the runner and the host cannot disagree about what is
#   acceptable. It only defines functions, so sourcing it has no side effects;
#   the last statement is a function definition, which makes `.` return 0.
#
# THESE CHECKS ARE NEWLINE-SAFE - DO NOT REINTRODUCE `grep` AS A GATE
#   The previous implementation validated with
#       printf '%s' "${v}" | grep -Eq '^[0-9a-f]{40}$'
#   which is LINE-based: `grep -q` succeeds when ANY line of the input matches,
#   so $'aaaa...\nrm -rf /tmp/x' passed the "exactly 40 hex characters" gate,
#   the "7-40 hex characters" gate and the deploy-dir gate. Everything below is
#   a `case` glob instead: a glob can only match a newline if the pattern names
#   one, so a multi-line value always falls into the reject arm. (`grep` is
#   still used to READ the operator's env file, where input is line-oriented by
#   definition and the result is re-validated - never as an accept/reject gate.)
#
# WHAT THESE FUNCTIONS NEVER DO
#   Print a value that was not passed to them as an argument, or print anything
#   at all from the operator's env file except the specific variable assignment
#   they were asked about - and then only its name is ever logged by callers.
#   The ONE carve-out is a NUMERIC PORT field in an error message: a port that
#   has already been proven to be digits-only is echoed back, because "the port
#   is out of range" is not actionable without the number. A value that has NOT
#   been proven digits-only is never echoed, whatever it is - an operator-owned
#   value can contain a line break, which would inject a workflow command into
#   the CI log.
#
# CALLING CONVENTION
#   Callers run under `set -euo pipefail`, so every function here returns an
#   explicit status and is meant to be invoked as `if ! fn ...; then` or
#   `fn ... || return 1`. No function relies on errexit.

# ---------------------------------------------------------------------------
# Value validation (newline-safe; see the header)
# ---------------------------------------------------------------------------

# validate_deploy_dir <value>
#   Accepts a plain absolute path: a leading '/', then only [A-Za-z0-9._/-].
#   Same character class the previous grep gate used - deliberately not
#   weakened - but a literal newline now makes the glob fail instead of being
#   silently ignored.
validate_deploy_dir() {
  local v="${1-}"
  case "${v}" in
    /*) : ;;
    *) return 1 ;;
  esac
  case "${v}" in
    *[!A-Za-z0-9._/-]*) return 1 ;;
  esac
  return 0
}

# validate_image_tag <value>
#   Exactly 40 lowercase hex characters - the full git SHA CI publishes.
#   A short prefix cannot be checked out by git nor pulled from the registry,
#   and `latest` is not an immutable rollback target.
validate_image_tag() {
  local v="${1-}"
  case "${v}" in
    '' | *[!0-9a-f]*) return 1 ;;
  esac
  [ "${#v}" -eq 40 ]
}

# validate_env_filename <value>
#   A BARE FILE NAME inside the deploy dir: no directory component, no '..'.
#   The override used to accept an absolute path, which let a repository
#   variable point the pre-flight scan at any file on the host and have its
#   contents surface in the CI log; the whole point of the variable is to name
#   one of the env files sitting next to the compose file.
validate_env_filename() {
  local v="${1-}"
  case "${v}" in
    '' | '.' | '..') return 1 ;;
    *[!A-Za-z0-9._-]*) return 1 ;;
  esac
  return 0
}

# validate_env_override <value>
#   As above, but "" (the variable is unset) means "auto-detect".
validate_env_override() {
  local v="${1-}"
  if [ -z "${v}" ]; then
    return 0
  fi
  validate_env_filename "${v}"
}

# validate_probe_url <value>
#   A plain http(s) URL with no character that a shell, or ssh's re-parse of
#   its argv, could treat as syntax. '[' and ']' are excluded, so IPv6
#   literals are not accepted - use a hostname or an IPv4 address.
validate_probe_url() {
  local v="${1-}"
  case "${v}" in
    http://?* | https://?*) : ;;
    *) return 1 ;;
  esac
  case "${v}" in
    *[!A-Za-z0-9._:/-]*) return 1 ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
# Reading the operator's env file
# ---------------------------------------------------------------------------

# env_file_value <env_file> <variable>
#   Echoes the LAST assignment of <variable> in <env_file>, or "" when absent.
#   An optional leading `export ` is accepted: Compose's own parser accepts
#   `export VAR=value` and it is a common .env idiom, but the old scan used
#   `^[[:space:]]*VAR=` and reported such a variable as "missing or empty", so
#   an operator using it could never deploy at all. A trailing CR is stripped so
#   a CRLF file behaves like an LF one. Last assignment wins, matching Compose.
env_file_value() {
  local env_file="$1" var="$2"
  grep -E "^[[:space:]]*(export[[:space:]]+)?${var}=" "${env_file}" \
    | tail -n 1 \
    | sed -e 's/\r$//' -e 's/^[^=]*=//' || true
}

# env_value_is_single_quoted <raw value>
#   Compose takes a single-quoted .env value VERBATIM (no interpolation), so
#   such a value is exempt from the `$` re-interpolation check below.
env_value_is_single_quoted() {
  local raw="${1-}"
  case "${raw}" in
    \'*\') return 0 ;;
  esac
  return 1
}

# env_value_unquoted <raw value>
#   Strips one layer of matching single or double quotes so that `VAR=""` and
#   `VAR=''` count as EMPTY. Compose interpolates both to an empty string, which
#   is exactly the failure the pre-flight exists to catch, and the old check
#   (which compared the raw text against "") let both through.
env_value_unquoted() {
  local raw="${1-}"
  case "${raw}" in
    \"*\") printf '%s' "${raw:1:${#raw}-2}" ;;
    \'*\') printf '%s' "${raw:1:${#raw}-2}" ;;
    *) printf '%s' "${raw}" ;;
  esac
}

# env_value_has_literal_dollar <raw value>
#   True when, after removing the `$$` escapes Compose uses for a literal '$',
#   a '$' REMAINS. Compose re-interpolates env-file values, so a surviving '$'
#   means the value the container receives is not the value in the file. The
#   canonical casualty is an unescaped BCrypt ADMIN_PASSWORD_HASH
#   (`$2a$10$N9qo...`): Compose eats the `$NAME` part, the placeholder Flyway
#   resolves at boot no longer matches the stored hash, and the backend
#   restart-loops before /actuator/health ever answers.
env_value_has_literal_dollar() {
  local raw="${1-}" without_escapes
  without_escapes="$(printf '%s' "${raw}" | sed -e 's/\$\$//g')"
  case "${without_escapes}" in
    *'$'*) return 0 ;;
  esac
  return 1
}

# is_bcrypt_hash <raw value>
#   True for the exact shape a BCrypt generator prints: a `$2a$`/`$2b$`/`$2x$`/
#   `$2y$` prefix, a two-digit cost, another '$', then the 53-character
#   salt+digest of [A-Za-z0-9./] - 60 characters in total.
#   WHY THIS EXISTS: such a value contains several '\$' and cannot be written in
#   the escape-free form the '\$' rule below wants, yet it is precisely the value
#   `htpasswd -bnBC 10` / python-bcrypt produce and the operator is told to use.
#   Failing the deploy for it would block the one value the deploy needs, so
#   require_env_vars reports it as a WARNING instead (see there) - the shape test
#   is what keeps that warning from covering a genuine typo such as `$abc123`.
#   Newline-safe like everything else here: a 60-character glob can never match a
#   value that contains a newline, so a multi-line payload cannot pass this.
is_bcrypt_hash() {
  local v="${1-}"
  case "${v}" in
    '$2a$'[0-9][0-9]'$'*|'$2b$'[0-9][0-9]'$'*|'$2x$'[0-9][0-9]'$'*|'$2y$'[0-9][0-9]'$'*) ;;
    *) return 1 ;;
  esac
  if [ "${#v}" -ne 60 ]; then
    return 1
  fi
  case "${v#???????}" in
    *[!A-Za-z0-9./]*) return 1 ;;
  esac
  return 0
}

# resolve_env_file <deploy_dir> <override>
#   Echoes the env file that will be used, or returns 1 when there is none.
#   With an override the path is used AS GIVEN and never silently falls back:
#   an operator who names a file must be told it is missing, not quietly handed
#   a different one.
#   Without one the order is `.env.staging` FIRST, then `.env`. `.env.staging`
#   is the primary name every other artifact in this slice documents (the
#   compose header, .env.staging.example, docker/README.md, docs/deployment.md).
#   Probing `.env` first was actively harmful: the DEV stack's docs tell
#   operators to `cp .env.example .env`, so a stale root `.env` in the deploy
#   dir is realistic, and it used to shadow a perfectly valid `.env.staging` and
#   abort the deploy.
resolve_env_file() {
  local deploy_dir="$1" override="$2" candidate
  if [ -n "${override}" ]; then
    printf '%s' "${deploy_dir}/${override}"
    return 0
  fi
  for candidate in "${deploy_dir}/.env.staging" "${deploy_dir}/.env"; do
    if [ -f "${candidate}" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  return 1
}

# env_file_help <deploy_dir> <override>
#   The actionable message printed whenever no usable env file was found. It
#   names BOTH auto-detect candidates, in resolution order, so the operator is
#   never told to fix a file the workflow was not going to use.
env_file_help() {
  local deploy_dir="$1" override="$2"
  {
    echo "ERROR: no usable env file found for the staging stack."
    echo "       Looked for ${deploy_dir}/.env.staging (the documented primary name),"
    echo "       then ${deploy_dir}/.env."
    echo "       STAGING_ENV_FILE override: ${override:-(not set)}"
    echo "       Refusing to continue: without this file Compose interpolates \${VAR}"
    echo "       placeholders to empty strings and the stack comes up broken."
    echo "       Create one from the tracked template, e.g.:"
    echo "         cp .env.staging.example ${deploy_dir}/.env.staging"
    echo "       then fill in every value it marks as required. The file is"
    echo "       operator-owned: this workflow never creates or overwrites it."
  } >&2
}

# require_env_vars <compose_file> <env_file>
#   Fails (rc 1, with an actionable message) unless every ${VAR:?} guard in the
#   compose file has a value that will survive Compose interpolation.
#   The required set is read from the compose file's own fail-fast guards rather
#   than hardcoded, so it can never drift from the stack definition; comment
#   lines are stripped first so commented-out examples are not requirements.
#   IMAGE_TAG is excluded because the workflow supplies it in the shell
#   environment, which takes precedence over the env file.
#   A value that LOOKS like the BCrypt hash this stack requires
#   (ADMIN_PASSWORD_HASH) is a WARNING, not a failure: it is the one value an
#   operator cannot reasonably write any other way, and Compose's treatment of
#   an unescaped '$' in an env file is not the same on every Compose version -
#   only the running backend can confirm whether it was mangled, and the health
#   check plus rollback are the mechanism for that. Every OTHER literal '$' is
#   still a hard failure, because for those the operator has no excuse and the
#   damage is silent (an empty JWT signing key, an empty DB password).
#   Only variable NAMES are ever printed - never a value.
require_env_vars() {
  local compose_file="$1" env_file="$2"
  local required v raw effective
  local missing="" unsafe="" bcrypt_unescaped=""

  required="$(grep -vE '^[[:space:]]*#' "${compose_file}" \
    | grep -oE '\$\{[A-Z_][A-Z0-9_]*:\?' \
    | sed -e 's/^[$]{//' -e 's/:?$//' \
    | sort -u | grep -vx 'IMAGE_TAG' || true)"

  for v in ${required}; do
    raw="$(env_file_value "${env_file}" "${v}")"
    effective="$(env_value_unquoted "${raw}")"
    if [ -z "${effective}" ]; then
      missing="${missing} ${v}"
      continue
    fi
    if ! env_value_is_single_quoted "${raw}" && env_value_has_literal_dollar "${raw}"; then
      if is_bcrypt_hash "${raw}"; then
        bcrypt_unescaped="${bcrypt_unescaped} ${v}"
      else
        unsafe="${unsafe} ${v}"
      fi
    fi
  done

  if [ -n "${missing}" ]; then
    echo "ERROR: missing or empty in ${env_file}:${missing}" >&2
    echo "       These are declared with \${VAR:?} in ${compose_file}, so Compose would abort or the container would fail to start without them." >&2
    echo "       'export VAR=value' is accepted. An explicitly empty value (VAR=, VAR=\"\" or VAR='') counts as missing." >&2
    return 1
  fi

  if [ -n "${bcrypt_unescaped}" ]; then
    echo "WARNING: these variables in ${env_file} hold an unescaped BCrypt hash:${bcrypt_unescaped}" >&2
    echo "         Compose applies interpolation to unquoted and double-quoted env-file values, so a hash written bare may reach the container mangled - the backend then restart-loops before /actuator/health ever answers." >&2
    echo "         The deploy PROCEEDS on purpose: this is the value a BCrypt generator prints, and only the running backend can confirm the mangle. If the health check fails for this revision, this is the first thing to check." >&2
    echo "         Make it unambiguous by single-quoting the whole value in ${env_file} - Compose takes a single-quoted value literally, so that form is correct whether or not your Compose version interpolates env files. Escaping every '\$' as '\$\$' is the other option, but it is the version-dependent one." >&2
  fi

  if [ -n "${unsafe}" ]; then
    echo "ERROR: these variables in ${env_file} contain a '\$' that Compose will re-interpolate:${unsafe}" >&2
    echo "       Compose expands '\$' inside env-file values, so what the container receives is NOT what is written in the file - and for a secret that means an empty value (an empty JWT signing key, an empty DB password) that nothing reports until the container is already broken." >&2
    echo "       Escape every literal '\$' as '\$\$', or wrap the whole value in single quotes (Compose uses single-quoted values verbatim)." >&2
    echo "       This is a hard failure rather than a warning on purpose: the old warning let a deployment proceed with a value that could not work." >&2
    return 1
  fi

  return 0
}

# ---------------------------------------------------------------------------
# Probe URLs
# ---------------------------------------------------------------------------

# url_port <validated url>
#   Echoes the port from the URL's authority, or "" when it has none.
url_port() {
  local v="${1-}" authority
  v="${v#*://}"
  authority="${v%%/*}"
  case "${authority}" in
    *:*) printf '%s' "${authority##*:}" ;;
    *) printf '' ;;
  esac
}

# resolve_probe_url <role> <env_file> <explicit_override>
#   role is "backend" or "frontend". Echoes the URL health-check.sh should probe.
#
#   The compose file publishes the backend on
#   127.0.0.1:${STAGING_BACKEND_PORT:-18080} and the frontend on
#   ${STAGING_FRONTEND_PORT:-8081}, and .env.staging.example advertises both as
#   configurable - but the workflow used to hardcode 18080/8081. An operator who
#   set STAGING_FRONTEND_PORT=9081 got a perfectly healthy stack, a probe aimed
#   at a closed port, and an automatic rollback of a good deploy reported as
#   unhealthy. The port is now read back from the env file the pre-flight
#   already opens, so the setting is never silently ignored.
#   An explicit URL override still wins, but a disagreement between it and a
#   port SET IN THE FILE is a hard error (rc 1) instead of a quiet surprise -
#   but only when the override NAMES a port of its own. An override with no port
#   (`https://staging-api.example.com`, the public URL in front of a
#   loopback-published stack) cannot disagree with the file's publish port and
#   must not be rejected: the file's port describes where the stack listens on
#   the host, the override where this run should probe.
#   A port that is not a plain decimal number is refused outright (rc 1): text
#   that is not a number at all, and a LEADING ZERO such as '08080', which
#   cannot be relied on to mean the same port to Compose as it does to the probe
#   URL - only a value this function has proven to be a plain decimal port can
#   be probed safely.
resolve_probe_url() {
  local role="$1" env_file="$2" override="$3"
  local port_var url_var default_port file_port port override_port

  case "${role}" in
    backend)
      port_var='STAGING_BACKEND_PORT'
      url_var='STAGING_BACKEND_URL'
      default_port='18080'
      ;;
    frontend)
      port_var='STAGING_FRONTEND_PORT'
      url_var='STAGING_FRONTEND_URL'
      default_port='8081'
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
      # The VALUE is deliberately not echoed here, only the variable name: this
      # is the one branch that runs before anything has been proven about it, so
      # the text is arbitrary - it may hold a line break (which would inject a
      # workflow command into the log) or any other operator-typed content. See
      # the invariant at the top of this file.
      echo "ERROR: ${port_var} in ${env_file} is not a number." >&2
      return 1
      ;;
  esac
  # A leading zero is refused, not normalised, and this test MUST stay ahead of
  # the range test below: '08080' passes the digit test above but is not compared
  # as a decimal number by `-lt`/`-gt`, so it can slip through the range check -
  # leaving the probe pointed at 08080 while Compose publishes whatever it makes
  # of that text. Refusing is the only option that cannot let the probe URL and
  # the published port diverge. A bare '0' does not match this glob and is
  # refused by the range test below.
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
        echo "       The stack is published on ${port} (docker-compose.staging.yml maps it from ${port_var}),"
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

# staging_context <deploy_dir> <env_file_override>
#   Validates both arguments and resolves the env file, setting the globals
#   DEPLOY_DIR, COMPOSE_FILE and ENV_FILE. Returns 1 after printing the
#   actionable message when the configuration is unusable.
#   This is the ONE resolution path: deploy, health check, rollback and
#   diagnostics all call it, so they cannot drift apart again.
staging_context() {
  local deploy_dir="$1" override="$2" resolved

  if ! validate_deploy_dir "${deploy_dir}"; then
    echo "ERROR: the deploy dir must be an absolute path using only [A-Za-z0-9._/-]." >&2
    return 1
  fi
  if ! validate_env_override "${override}"; then
    echo "ERROR: STAGING_ENV_FILE must be a bare file name inside the deploy dir - no '/', no '..', no shell metacharacters." >&2
    return 1
  fi

  DEPLOY_DIR="${deploy_dir}"
  COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.staging.yml"
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
