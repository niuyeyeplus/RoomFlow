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
