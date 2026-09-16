#!/usr/bin/env bash
#
# RoomFlow post-deploy health check for the STAGING stack.
#
# Runs against the two loopback-bound HTTP endpoints published by
# docker/docker-compose.staging.yml:
#   * backend  -> http://127.0.0.1:${STAGING_BACKEND_PORT:-18080}/actuator/health
#   * frontend -> http://127.0.0.1:${STAGING_FRONTEND_PORT:-8081}/
#
# Usage: docker/health-check.sh [BACKEND_URL] [FRONTEND_URL]
#
# POSITIONAL ARGUMENTS (both optional)
#   $1 BACKEND_URL   default: ${BACKEND_URL:-http://127.0.0.1:${STAGING_BACKEND_PORT:-18080}}
#   $2 FRONTEND_URL  default: ${FRONTEND_URL:-http://127.0.0.1:${STAGING_FRONTEND_PORT:-8081}}
#
# ENVIRONMENT OVERRIDES
#   BACKEND_URL              backend base URL (no /actuator/health suffix)
#   FRONTEND_URL             frontend base URL
#   HEALTH_TIMEOUT_SECONDS   total budget per check, default 180
#   HEALTH_INTERVAL_SECONDS  delay between retries, default 5
#   CURL_MAX_TIME            per-request curl timeout, default 10
#
# EXIT STATUS
#   0  both checks passed
#   1  at least one check failed (the failing check is named on stderr)
#   2  bad invocation or unusable environment: `curl` is missing, or a tuning
#      variable is not a positive integer. `0` counts as invalid — a zero budget
#      collapsed the retry loop to a single attempt (and `sleep 0` spun it) while
#      the message claimed "positive", so the retry the deploy workflow depends
#      on was silently gone. See require_positive_int below.
#
# WHO RUNS THIS / WHERE
#   `curl` must be installed on the host that runs the script. Every middleware
#   port in the staging stack is bound to 127.0.0.1, so this script MUST run on
#   the staging host itself (or inside an SSH session onto that host) — running
#   it from the GitHub runner would probe the runner's own loopback and always
#   fail. The deploy workflow is expected to invoke it over SSH.
#
#   From the repository root on the staging host:
#     bash docker/health-check.sh
#     bash docker/health-check.sh http://127.0.0.1:18080 http://127.0.0.1:8081
#
# SECRETS
#   This script never reads, echoes or logs a credential. `set -x` is
#   deliberately NOT used, and only URLs and HTTP status codes are printed, so
#   the output is safe to attach to a CI job log.
#
# `set -euo pipefail` is intentional: an unexpected failure anywhere must abort
# rather than report a false pass.
set -euo pipefail

BACKEND_URL="${1:-${BACKEND_URL:-http://127.0.0.1:${STAGING_BACKEND_PORT:-18080}}}"
FRONTEND_URL="${2:-${FRONTEND_URL:-http://127.0.0.1:${STAGING_FRONTEND_PORT:-8081}}}"

HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"
CURL_MAX_TIME="${CURL_MAX_TIME:-10}"

# Strip a trailing slash so "${URL}/actuator/health" never becomes "//actuator".
BACKEND_URL="${BACKEND_URL%/}"
FRONTEND_URL="${FRONTEND_URL%/}"

fail_usage() {
  echo "health-check: $1" >&2
  exit 2
}

command -v curl >/dev/null 2>&1 || fail_usage "curl is required but was not found on PATH (the check must run on the staging host)."

# Requires a decimal integer greater than 0.
#
# This runs AFTER the `${VAR:-default}` assignments above, which already collapse
# an UNSET or EMPTY value to the default. That is deliberate and documented: an
# empty value means "use the default", which is why the previous version's `''`
# arm was unreachable dead code. There is no `''` arm here either; if the `:-`
# defaults were ever removed, an empty value would fall through to the second
# `case` and be rejected rather than silently accepted.
#
# `0` IS rejected even though it is a syntactically valid integer:
#   HEALTH_TIMEOUT_SECONDS=0   -> the deadline is already in the past, so exactly
#                                 one attempt happens and the check always fails
#   HEALTH_INTERVAL_SECONDS=0   -> `sleep 0` returns immediately; the retry loop
#                                 becomes a busy loop
#   CURL_MAX_TIME=0             -> curl aborts every request instantly (exit 28)
# All three used to be accepted while the error message said "must be a positive
# integer", i.e. validation that did not match its own contract.
#
# The offending VALUE is deliberately NOT interpolated into either message. The
# first arm is taken precisely when the value is not proven numeric, and this
# script's stderr is relayed into a CI job log: a value such as
# HEALTH_TIMEOUT_SECONDS=$'x\n::error::pwned' would otherwise echo a newline and
# inject a workflow command into the log. Naming the variable and the rule keeps
# the message actionable without echoing the value, its length or an escaped or
# truncated form of it (a length is still a weak oracle, and truncating risks
# re-introducing the newline).
require_positive_int() {
  local name="$1" value="$2"
  case "${value}" in
    *[!0-9]*)
      fail_usage "${name} must be a positive integer greater than 0 (got a value that is not a plain decimal integer)" ;;
  esac
  case "${value}" in
    *[1-9]*) : ;;
    # Only reachable with a digits-only value containing no 1-9, i.e. zero.
    *) fail_usage "${name} must be a positive integer greater than 0 (got zero)" ;;
  esac
}

require_positive_int HEALTH_TIMEOUT_SECONDS "${HEALTH_TIMEOUT_SECONDS}"
require_positive_int HEALTH_INTERVAL_SECONDS "${HEALTH_INTERVAL_SECONDS}"
require_positive_int CURL_MAX_TIME "${CURL_MAX_TIME}"

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "${TMP_DIR}"; }
trap cleanup EXIT

# Fetches a URL into a file, echoing the HTTP status code on stdout and writing
# curl's stderr into a sibling file (so a transport-level failure such as
# "connection refused" can be reported alongside the status code).
#
# A non-2xx status is NOT a curl error, and a transport failure must not abort
# the script under `set -e`, so the curl call is guarded with `|| true` and the
# caller inspects the returned code instead.
http_get() {
  local url="$1" body_file="$2" err_file="$3"
  curl --silent --show-error --location \
    --max-time "${CURL_MAX_TIME}" \
    --output "${body_file}" \
    --write-out '%{http_code}' \
    "${url}" 2>"${err_file}" || true
}

# ── JSON parsing helpers (no jq) ─────────────────────────────────────────────
#
# `json_top_level_members` flattens a JSON object down to its TOP-LEVEL members,
# dropping every nested object/array and everything inside it. It is depth-aware
# and string-aware, so a brace or bracket inside a string value cannot confuse it.
#
# Why this exists: the backend check must not be fooled by a NESTED component
# status. This body
#   {"status":"DOWN","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
# contains the exact substring `"status":"UP"`, so the unanchored grep this
# script used to run over the whole body reported a DOWN backend as healthy — a
# FALSE PASS that would have let a broken deploy through the gate with no
# rollback. That body shape is reachable: Spring's /actuator/health only hides it
# while `management.endpoint.health.show-details: when-authorized` keeps the
# response compact, and flipping that setting to `always` (or pointing the probe
# at an authenticated endpoint) returns the component tree. The check has to be
# correct on its own rather than depend on a setting in another file.
#
# Written in pure bash on purpose: `jq` is not assumed to exist on the staging
# host, and this keeps the dependency list at exactly one binary (curl).
json_top_level_members() {
  local json="$1"
  local i=0 len=${#json} depth=0 ch out=''
  local in_string=0 escaped=0

  while [ "${i}" -lt "${len}" ]; do
    ch="${json:${i}:1}"
    if [ "${in_string}" -eq 1 ]; then
      # Inside a string literal: a backslash escapes the next character, so
      # `\"` does not terminate the string. Only text belonging to a direct
      # member of the top-level object is kept — the opening brace bumped depth
      # from 0 to 1, so top-level members are seen at depth 1 while anything
      # inside a nested container is at depth >= 2 and is dropped.
      if [ "${depth}" -eq 1 ]; then out+="${ch}"; fi
      if [ "${escaped}" -eq 1 ]; then
        escaped=0
      elif [ "${ch}" = '\' ]; then
        escaped=1
      elif [ "${ch}" = '"' ]; then
        in_string=0
      fi
    else
      case "${ch}" in
        # Braces and brackets are dropped on purpose: the flattened output keeps
        # only top-level member text, which is all a status comparison needs.
        '{' | '[') depth=$(( depth + 1 )) ;;
        '}' | ']') depth=$(( depth - 1 )) ;;
        '"') in_string=1; if [ "${depth}" -eq 1 ]; then out+='"'; fi ;;
        *) if [ "${depth}" -eq 1 ]; then out+="${ch}"; fi ;;
      esac
    fi
    i=$(( i + 1 ))
  done

  printf '%s' "${out}"
}

# True only when the TOP-LEVEL "status" member of the body is exactly "UP".
#
# A nested `"status":"UP"` inside `components` is removed by the flattening
# above, so it cannot satisfy this test — that is the whole point. The trailing
# `([,}]|$)` anchor additionally rejects a malformed body in which the literal
# text `"status":"UP"` appears inside some other top-level string.
#
# Only the first 4 KiB are scanned: a real /actuator/health body is a few dozen
# bytes, so the cap never bites in practice. It exists so that a huge non-JSON
# body served with HTTP 200 (e.g. an HTML error page from a proxy in front of
# the backend) cannot turn the character-by-character scan into a long loop. A
# truncated or non-JSON body simply fails to match, which is the safe direction.
backend_status_is_up() {
  local body_file="$1"
  local body members
  body="$(head -c 4096 "${body_file}" 2>/dev/null)" || true
  members="$(json_top_level_members "${body}")" || true
  printf '%s' "${members}" |
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"[[:space:]]*([,}]|$)'
}

# Polls $BACKEND_URL/actuator/health until it returns HTTP 200 AND that body's
# TOP-LEVEL status is UP.
#
# /actuator/health returns {"status":"UP",...} on success. A 200 whose body says
# "DOWN"/"OUT_OF_SERVICE" — or is not JSON at all — is a failed check, not a
# pass: the endpoint answering at all is not the same thing as the stack being up.
check_backend() {
  local url="${BACKEND_URL}/actuator/health"
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
  local attempt=0 last_code='none' last_error='' code body_file err_file ok=0

  body_file="${TMP_DIR}/backend.body"
  err_file="${TMP_DIR}/backend.err"

  echo "health-check: backend  ${url} (budget ${HEALTH_TIMEOUT_SECONDS}s, interval ${HEALTH_INTERVAL_SECONDS}s)"

  while :; do
    attempt=$(( attempt + 1 ))
    code="$(http_get "${url}" "${body_file}" "${err_file}")"
    last_code="${code:-none}"
    last_error="$(cat "${err_file}" 2>/dev/null || true)"

    if [ "${code}" = "200" ]; then
      if backend_status_is_up "${body_file}"; then
        echo "health-check: backend  attempt ${attempt}: HTTP 200, top-level status UP"
        ok=1
        break
      fi
      echo "health-check: backend  attempt ${attempt}: HTTP 200 but the top-level status is not UP"
      # `|| true`: a sanitised, truncated body is diagnostics only and must
      # never abort the retry loop under `set -e`/`pipefail`. The body of
      # /actuator/health carries no credential.
      last_error="HTTP 200 with a non-UP top-level status: $(tr -d '\n' <"${body_file}" 2>/dev/null | cut -c1-200 || true)"
    else
      echo "health-check: backend  attempt ${attempt}: HTTP ${last_code}${last_error:+ (${last_error})}"
    fi

    if [ "$(date +%s)" -ge "${deadline}" ]; then
      break
    fi
    sleep "${HEALTH_INTERVAL_SECONDS}"
  done

  if [ "${ok}" -eq 1 ]; then
    return 0
  fi

  echo "health-check: FAILED backend check — ${url} did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s (${attempt} attempt(s)); last HTTP status ${last_code}${last_error:+; last error: ${last_error}}" >&2
  return 1
}

# Polls $FRONTEND_URL/ until it returns HTTP 200 with a body that has actual
# content. Redirects are followed (--location), so an nginx rewrite to
# /index.html is accepted, and an empty or whitespace-only 200 is rejected
# because that is what a broken nginx root directory looks like.
check_frontend() {
  local url="${FRONTEND_URL}/"
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
  local attempt=0 last_code='none' last_error='' code body_file err_file ok=0

  body_file="${TMP_DIR}/frontend.body"
  err_file="${TMP_DIR}/frontend.err"

  echo "health-check: frontend ${url} (budget ${HEALTH_TIMEOUT_SECONDS}s, interval ${HEALTH_INTERVAL_SECONDS}s)"

  while :; do
    attempt=$(( attempt + 1 ))
    code="$(http_get "${url}" "${body_file}" "${err_file}")"
    last_code="${code:-none}"
    last_error="$(cat "${err_file}" 2>/dev/null || true)"

    if [ "${code}" = "200" ]; then
      # `[ -s file ]` was only a byte-count test, and a body of "   \n  " has
      # bytes but no content — a whitespace-only index is exactly as broken as
      # an empty one. Require at least one non-whitespace character instead.
      if grep -q '[^[:space:]]' "${body_file}" 2>/dev/null; then
        echo "health-check: frontend attempt ${attempt}: HTTP 200, non-blank body"
        ok=1
        break
      fi
      echo "health-check: frontend attempt ${attempt}: HTTP 200 but the response body is empty or whitespace-only"
      last_error='HTTP 200 with an empty or whitespace-only body'
    else
      echo "health-check: frontend attempt ${attempt}: HTTP ${last_code}${last_error:+ (${last_error})}"
    fi

    if [ "$(date +%s)" -ge "${deadline}" ]; then
      break
    fi
    sleep "${HEALTH_INTERVAL_SECONDS}"
  done

  if [ "${ok}" -eq 1 ]; then
    return 0
  fi

  echo "health-check: FAILED frontend check — ${url} did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s (${attempt} attempt(s)); last HTTP status ${last_code}${last_error:+; last error: ${last_error}}" >&2
  return 1
}

# Both checks always run, even when the first one fails, so a single CI run
# reports everything that is broken instead of only the first symptom.
backend_ok=0
frontend_ok=0

check_backend || backend_ok=$?
check_frontend || frontend_ok=$?

if [ "${backend_ok}" -eq 0 ] && [ "${frontend_ok}" -eq 0 ]; then
  echo "health-check: OK — backend and frontend are healthy"
  exit 0
fi

# Built with `if` rather than `[ ... ] && ...`: a bare `[ test ] && cmd` that
# evaluates false returns non-zero and would abort the script under `set -e`.
failed=''
if [ "${backend_ok}" -ne 0 ]; then
  failed='backend'
fi
if [ "${frontend_ok}" -ne 0 ]; then
  failed="${failed:+${failed} and }frontend"
fi
echo "health-check: FAILED — ${failed} check(s) did not pass" >&2
exit 1
