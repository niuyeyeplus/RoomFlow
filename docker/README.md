# RoomFlow Docker deployment

## Prerequisite: `.env`

Compose interpolates `${VAR}` placeholders from the shell environment and the
`.env` file in the **project directory** - not from `env_file:` entries (those
only inject variables into the container at runtime).

```powershell
# From the repository root
cp .env.example .env   # then fill in DB_PASSWORD, MYSQL_ROOT_PASSWORD,
                       # REDIS_PASSWORD, RABBITMQ_PASSWORD, JWT_SECRET, ...
```

The following must be non-empty or middleware refuses to start: `DB_PASSWORD`,
`MYSQL_ROOT_PASSWORD`, `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `JWT_SECRET`.

## Start the stack

Always run from the **repository root** (not `docker/`):

```bash
docker compose --project-directory . --env-file .env -f docker/docker-compose.yml up -d
```

Why the flags are mandatory:

- The project directory defaults to the directory of the first `-f` file
  (`docker/`), so Compose would look for `docker/.env`, which does not exist.
- `--project-directory .` points it at the repo root; `--env-file .env` is
  explicit and fails fast with a clear error if `.env` is missing.
- Running plain `docker compose -f docker/docker-compose.yml up` produces
  empty `${DB_PASSWORD}`-style values (warnings only) and MySQL/Redis then
  fail to start - this was the original deployment break.

Other commands:

```bash
docker compose --project-directory . --env-file .env -f docker/docker-compose.yml ps
docker compose --project-directory . --env-file .env -f docker/docker-compose.yml logs -f backend
docker compose --project-directory . --env-file .env -f docker/docker-compose.yml down   # no -v: keeps named volumes
```

## Topology

- `frontend` (nginx, port 80) serves the SPA and reverse-proxies `/api/` to
  `backend:8080` on the internal `roomflow-net` bridge network. The frontend
  image is built without `VITE_API_BASE_URL`, so axios uses origin-relative
  `/api/**` paths; without the nginx `location /api/` block every API call
  would receive `index.html`.
- `backend` exposes `8080` directly as well (dev convenience); nginx reaches
  it over the internal network regardless.
- `mysql` (13306), `redis` (16379), `rabbitmq` (15673 AMQP, 15672 management
  UI) are bound to `127.0.0.1` only. Reach them remotely via SSH tunnel -
  see `docs/operations/middleware.md`.

## Notes and known limitations (dev-oriented stack)

- Backend healthcheck uses BusyBox `wget` because `eclipse-temurin:*-alpine`
  ships no `curl`. `/actuator/health` is `permitAll` in `SecurityConfig`.
- Redis password is passed via `sh -c "... $$REDIS_PASSWORD"` so
  `docker inspect .Config.Cmd` does not contain the plaintext. It is still
  visible in `.Config.Env` - acceptable for dev; use Docker secrets or a
  mounted redis.conf for production.
- All services use `restart: unless-stopped` and health-gated `depends_on`.
- Named volumes `mysql-data` / `redis-data` hold persistent state; avoid
  `down -v`.

## Staging stack

`docker/docker-compose.staging.yml` is a **separate, independent** stack. It is
built to run side by side with the dev stack on the same host without sharing
anything, so a mistake in one cannot destroy the other.

### Prerequisite: `.env.staging`

`.env.staging` is gitignored. The tracked template is
`.env.staging.example` (kept in git by an explicit `!.env.staging.example`
negation in `.gitignore`, which is required because the broader `.env.*`
pattern would otherwise hide it).

```bash
# From the repository root
cp .env.staging.example .env.staging   # then replace every CHANGE_ME
```

Fill in every `CHANGE_ME`, and apply the quoting rule described below under
**Single-quote any value containing `$` in `.env.staging`** — an unquoted (or
double-quoted) `$` in a value silently empties or mangles it no matter how
complete the file looks.

### Start the stack

Always run from the **repository root** (not `docker/`):

```bash
IMAGE_TAG=<40-char git sha> \
docker compose --project-directory . --env-file .env.staging \
  -f docker/docker-compose.staging.yml up -d
```

The same `--project-directory` / `--env-file` reasoning as the dev stack
applies: without `--project-directory .` Compose looks for `docker/.env`, and
without `--env-file .env.staging` it silently interpolates empty secrets.

Other commands:

```bash
docker compose --project-directory . --env-file .env.staging -f docker/docker-compose.staging.yml ps
docker compose --project-directory . --env-file .env.staging -f docker/docker-compose.staging.yml logs -f backend
docker compose --project-directory . --env-file .env.staging -f docker/docker-compose.staging.yml down   # no -v: keeps named volumes
```

### `IMAGE_TAG` is mandatory and immutable

The staging compose pins both images with the fail-fast form
`ghcr.io/niuyeyeplus/roomflow-{backend,frontend}:${IMAGE_TAG:?...}`, **not** the
dev stack's `${IMAGE_TAG:-latest}`. The `:-` form quietly falls back to the
mutable `latest` tag when the variable is unset, so a staging deploy could run
an image nobody chose and you could not tell afterwards which commit was live.
With `:?` Compose aborts before creating anything unless `IMAGE_TAG` is set.
Use the 40-char git SHA that CI pushed to GHCR.

### `ADMIN_PASSWORD_HASH` is required or the backend will not start

`backend/src/main/resources/application-staging.yml` declares the Flyway
placeholder `admin-password-hash: ${ADMIN_PASSWORD_HASH}` **with no default**.
Flyway resolves placeholders while migrating at boot, so a missing value kills
the backend during startup — before `/actuator/health` ever answers — and the
container restart-loops. The dev compose deliberately does not pass this
variable; the staging compose does, and it must stay that way.

The value is a **BCrypt hash generated offline**, never a plaintext password. A
BCrypt hash is made of `$` characters (`$2a$10$...`), which makes it the value
most likely to be mangled by the quoting rule below.

#### The value most likely to be written wrongly

Write the hash **single-quoted** so the container receives it verbatim:

```
ADMIN_PASSWORD_HASH='$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
```

`$$`-escaping works too but is the version-dependent option, and exporting the
variable from the shell is also safe — see the quoting rule below.

**A wrong value here is not stopped by the pre-flight check.** A BCrypt-*shaped*
value with an unescaped `$` is reported as a **WARNING** and the deploy
**proceeds** (see `.github/scripts/staging-deploy-lib.sh`) — deliberately, because
it is the one value an operator cannot reasonably write any other way and only
the running backend can confirm the mangle. The failure therefore surfaces later,
and it is a slow, confusing one. Symptom → cause → fix:

| | |
| --- | --- |
| **Symptom** | The deploy waits out the whole health-check budget, then fails and the revision is rolled back. |
| **Cause** | The value the container received is not the value written in `.env.staging`. If it ended up **empty**, the backend dies while still starting, so `/actuator/health` never answers; `restart: unless-stopped` restart-loops the container, `frontend` is held back by its `depends_on: backend: condition: service_healthy`, and `docker/health-check.sh` polls two URLs that cannot come up. A hash that is mangled but still **non-empty** can instead let the backend boot and seed the `admin` account with a hash matching no known password: the health check passes, nothing rolls back, but the admin login is broken. |
| **Fix** | Single-quote the hash in `.env.staging`, re-deploy, then `... logs backend` to confirm the startup error is gone (or, in the second case, log in as `admin` to confirm the hash took effect). |

`CORS_ALLOWED_ORIGINS` is guarded the same way even though the app would merely
default it to empty (no cross-origin requests allowed) — every staging deploy
should state its allowed origins explicitly rather than ship a stack whose
browsers reject the SPA's API calls.

### Single-quote any value containing `$` in `.env.staging`

Compose interpolates `--env-file` values a **second** time: the file is read,
then `${...}` inside the *values* is expanded again. That second pass applies to
**unquoted and double-quoted** values only — a **single-quoted** value is exempt
and is passed through verbatim (the quotes are stripped, nothing inside them is
expanded). So the container receives exactly the value you wrote, whatever
Compose version is in use:

```
RIGHT:  JWT_SECRET='$abc123'    # literal; correct on any Compose version
OK:     JWT_SECRET=$$abc123     # also valid: Compose turns $$ into one '$'
WRONG:  JWT_SECRET=$abc123      # expands the (unset) variable "abc123" -> empty
WRONG:  JWT_SECRET="$abc123"    # the same - double quotes do NOT protect a value
```

`$$`-escaping is the **alternative**, not the rule. It works by relying on the
double-interpolation semantics above, so whether `$$` means "one literal dollar"
or survives as two characters depends on the Compose version in use — that is
what makes it the version-dependent option. Prefer single quotes.

The `${VAR:?must be set ...}` guards in the compose file test what the env file
supplies, not what that text expands to, so **do not rely on a guard to catch
this** (whether the guard sees the raw or the expanded value depends on the
Compose version). A guard catches a variable that is **missing or empty in the
file**; it cannot catch a value that is non-empty in the file but re-interpolates
to something else.

What each startup-critical variable does when its value is empty or mangled:

| Variable | Failure mode | Compose guard |
| --- | --- | --- |
| `ADMIN_PASSWORD_HASH` | Backend dies during startup (Flyway placeholder) → restart-loop → health check burns its budget → rollback; a merely *wrong* hash can instead pass the health check and break the admin login | `${VAR:?...}` for missing only — see the callout above |
| `REDIS_PASSWORD` | Redis with **no authentication**. The `redis` service re-checks the password it actually received (after interpolation) and refuses to start, so this one fails loudly instead of coming up open | `${VAR:?...}` **plus** the runtime `command:` check |
| `JWT_SECRET` | Backend fails at startup on a too-short HMAC key → health check fails → rollback | `${VAR:?...}` |
| `DB_PASSWORD` | MySQL refuses to initialise with an empty credential on a fresh volume; the backend cannot connect | `${VAR:?...}` |
| `MYSQL_ROOT_PASSWORD` | MySQL 8.4 refuses to initialise with an empty root password, and the `mysqladmin ping` healthcheck never passes | `${VAR:?...}` |
| `RABBITMQ_PASSWORD` | RabbitMQ refuses to initialise with an empty credential; the backend cannot connect | `${VAR:?...}` |
| `RABBITMQ_USERNAME` | Same startup failure; far less likely to be mangled, since it is a plain word rather than a secret | `${VAR:?...}` |
| `CORS_ALLOWED_ORIGINS` | Not a startup failure — the app defaults it to empty and browsers then reject the SPA's API calls, which is why it is guarded anyway | `${VAR:?...}` |

If you are unsure, keep the value out of the file entirely and export it from the
shell before running Compose; an exported variable is already interpolated and is
never re-expanded:

```bash
export JWT_SECRET='...' ADMIN_PASSWORD_HASH='...'
docker compose --project-directory . --env-file .env.staging \
  -f docker/docker-compose.staging.yml up -d
```

The same rule is stated in the header of `docker/docker-compose.staging.yml` and
at the top of `.env.staging.example`.

### Isolation guarantees

| Concern | Dev | Staging |
| --- | --- | --- |
| Compose project | directory name | `roomflow-staging` (top-level `name:`) |
| Containers | `roomflow-*` | `roomflow-staging-*` |
| Network | `roomflow-net` | `staging-net` (project-scoped) |
| Volumes | `mysql-data`, `redis-data` | `staging-mysql-data`, `staging-redis-data` |
| MySQL | `127.0.0.1:13306` | `127.0.0.1:13307` |
| Redis | `127.0.0.1:16379` | `127.0.0.1:16380` |
| RabbitMQ AMQP / UI | `127.0.0.1:15673` / `15672` | `127.0.0.1:15674` / `15675` |
| Backend | `127.0.0.1:8080` | `127.0.0.1:18080` |
| Frontend | `0.0.0.0:80` | `${STAGING_FRONTEND_PORT:-8081}` |

Every middleware port and the backend port are bound to `127.0.0.1` only, so
they are reachable exclusively through an SSH tunnel. The frontend is the single
public entry point and its host port stays configurable.

`SPRING_PROFILES_ACTIVE` is hardcoded to `staging` rather than driven by a
variable: this file has one job, and hardcoding removes the failure mode where a
misconfigured pipeline exports `dev` and boots the staging host with the dev
profile.

All five services (mysql, redis, rabbitmq, backend, frontend) carry a
healthcheck, and `depends_on` is health-gated the same way as dev (backend waits
for the three middleware services, frontend waits for the backend). The backend
and frontend healthchecks use BusyBox `wget` because neither the
`eclipse-temurin:*-alpine` nor the nginx alpine image ships `curl`.

The Redis password still goes through `sh -c "... $$REDIS_PASSWORD"` so
`docker inspect .Config.Cmd` does not contain the plaintext. This is the same
accepted trade-off as dev, and it is honestly **not** a full secret-store
solution: the value remains visible in `.Config.Env` and in `ps` output. Use
Docker secrets or a mounted `redis.conf` if that is not acceptable.

### Post-deploy health check

`docker/health-check.sh` polls both public HTTP endpoints and exits non-zero
unless both are healthy.

```bash
# On the staging host, from the repository root:
bash docker/health-check.sh
bash docker/health-check.sh http://127.0.0.1:18080 http://127.0.0.1:8081
```

It defaults to `http://127.0.0.1:${STAGING_BACKEND_PORT:-18080}` and
`http://127.0.0.1:${STAGING_FRONTEND_PORT:-8081}`, and honours the
`BACKEND_URL`, `FRONTEND_URL`, `HEALTH_TIMEOUT_SECONDS` (180),
`HEALTH_INTERVAL_SECONDS` (5) and `CURL_MAX_TIME` (10) environment variables.

The backend check requires HTTP 200 **and** a body whose **top-level** `status`
is exactly `"UP"`; the frontend check requires HTTP 200 with a body containing at
least one non-whitespace character (an empty *or whitespace-only* index is how a
broken nginx root looks), and follows redirects. `curl` must be installed on the
machine that runs it, and because the ports are loopback-bound it must run **on
the staging host** (typically over SSH from the deploy workflow) — running it
from a GitHub runner would only probe the runner's own loopback. The script never
prints a credential, so its output is safe to attach to a CI log.

The top-level requirement is not cosmetic: `{"status":"DOWN","components":
{"db":{"status":"UP"}}}` contains the substring `"status":"UP"`, so a substring
match over the whole body would report a genuinely DOWN backend as healthy and
let a broken release through the gate with no rollback. The script strips nested
objects before comparing (pure bash — `jq` is not assumed to exist on the host).

Exit status — the deploy workflow distinguishes these, so they are part of the
script's interface:

| Code | Meaning |
| --- | --- |
| `0` | both checks passed |
| `1` | at least one check failed (the failing one is named on stderr) |
| `2` | bad invocation / unusable environment: `curl` missing, or a tuning variable that is not an integer greater than 0 |

The three tuning variables must be **positive** integers; `0` is rejected rather
than accepted as "no budget", because a zero timeout collapses the retry loop to
a single attempt while looking like a legitimate setting, and `0` for the
interval or the curl timeout makes the loop busy-spin. An **empty** value is not
an error — `${VAR:-default}` resolves it to the documented default (180 / 5 /
10), which keeps an unset variable in the workflow environment harmless.

### Line endings and the executable bit

`docker/health-check.sh` is shipped to a Linux host and run there, so its line
endings are load-bearing: a CRLF copy makes the host read `set -euo pipefail\r`
as an invalid shell option and the post-deploy check dies instantly (the deploy
is then reported unhealthy and rolled back). `.gitattributes` at the repository
root pins the files this deployment executes or parses — `*.sh`, `*.yml`,
`*.yaml`, `*.example`, `*.conf` — to `eol=lf`, so checkouts are byte-identical on
Windows and Linux.

The script's **executable bit is not stored by this repository's default git
config**: `core.filemode=false` is set on the Windows dev machines, so git records
`100644` even though the working copy is `0755`. The deploy workflow invokes it as
`bash ./health-check.sh` and does not depend on the bit, but if you add the file
(or change it) commit with the mode forced:

```bash
git update-index --chmod=+x docker/health-check.sh
git ls-files -s docker/health-check.sh   # must start with 100755
```
