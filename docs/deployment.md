# RoomFlow deployment — staging

This document describes the **staging** deployment: what the pipeline does, what
must be configured before it can work, how to run it, how to roll it back, and
what has *not* been verified. It is derived from the artifacts themselves
(`.github/workflows/deploy-staging.yml`, `.github/scripts/staging-deploy-lib.sh`,
`docker/docker-compose.staging.yml`, `docker/health-check.sh`,
`.env.staging.example`, `frontend/playwright.config.ts`,
`frontend/tests/e2e/helpers.ts` and `.github/workflows/ci.yml`), not from an
aspiration of how they should work.

Production is a separate slice — see [§11](#11-production-forward-pointer).

---

> ## ⚠ Read this before configuring anything
>
> Two repository-level settings will make this pipeline **silently do nothing**,
> and neither of them is visible from the workflow file:
>
> 1. **The default branch of this repository is `ci/github-actions`, not `main`.**
>    GitHub only fires a `workflow_run` workflow if the workflow file exists **on
>    the default branch**, so merging this slice into `main` can produce a green CI
>    run and **no deploy at all, with no error anywhere**. The manual entry point
>    (`workflow_dispatch`) is likewise only offered once the file is on the default
>    branch. See [§1](#the-default-branch-caveat) and [§10](#10-known-limitations).
> 2. **The `staging` environment does not exist yet, and GitHub auto-creates a
>    referenced environment with no protection rules.** The deploy job declares
>    `environment: staging`, so the gate looks present but protects nothing until
>    you add required reviewers, a deployment branch policy, and move the deploy
>    secrets to environment scope. See [§2.1](#21-the-staging-github-environment-required-and-inert-until-configured).

---

## 1. Overview

### What a staging deploy is

A staging deploy is: **take the images CI built for a specific commit on `main`,
pull them on the staging host by immutable tag, recreate the stack with Compose,
and prove the result is healthy — or put the previous version back.**

The stack itself (`docker/docker-compose.staging.yml`) is a five-service Compose
project named `roomflow-staging`: MySQL 8.4, Redis 8.2, RabbitMQ 4.3,
the Spring Boot backend, and the nginx-served Vue SPA. It is deliberately
independent from the dev stack — no shared network, volume, container name or
host port — so the two can run side by side on one host.

### Trigger chain

```
merge / push to main
        │
        ▼
CI workflow (.github/workflows/ci.yml)
  backend-lint-test ─┐
  frontend-check-build ─┴─► security-scan ─► docker-build-push
                                                │  pushes ghcr.io/niuyeyeplus/
                                                │  roomflow-{backend,frontend}
                                                │  tagged <full 40-char commit sha>
                                                ▼
                        Deploy Staging (.github/workflows/deploy-staging.yml)
                        trigger: `workflow_run` of CI, types [completed], branches [main]
                                 (+ `workflow_dispatch` for manual re-deploy / rollback)
                        gate:    `environment: staging` (inert until you configure it — §2.1)
                                                │
                                                ▼
                        resolve tag = github.event.workflow_run.head_sha
                        (GATE 1: non-empty, [0-9a-f] only, 7-40 chars
                         GATE 2: exactly 40 chars, i.e. the full immutable SHA)
                                                │
                                                ▼
                        checkout that revision; verify the three deploy artifacts exist
                        validate every value that will reach a remote command line
                          (rules from .github/scripts/staging-deploy-lib.sh)
                                                │
                                                ▼
                        SSH to the staging host (alias `roomflow-staging`)
                          ├─ capture the currently running tag (rollback target)
                          ├─ ship docker-compose.staging.yml + health-check.sh
                          │    + staging-deploy-lib.sh
                          ├─ docker login ghcr.io — ONLY if GHCR_READ_TOKEN is set
                          │    (the packages are public; the default path is skipped)
                          └─ docker compose up -d  with IMAGE_TAG=<sha>
                                                │
                                                ▼
                        post-deploy health check ON THE HOST  (docker/health-check.sh)
                                                │
                            ┌───────────────────┴───────────────────┐
                            ▼                                       ▼
                    exit 0 → healthy                    non-zero → automatic rollback
                    job green                                    to the previously
                                                                 running tag + re-check;
                                                                 job still FAILS
```

The host is reached through a single SSH alias (`roomflow-staging`) configured by
the workflow; `~/.ssh/roomflow_staging_deploy` is the private key the workflow
writes **on the runner** from the `STAGING_SSH_PRIVATE_KEY` secret.

### Notes that matter and are easy to get wrong

- **Both triggers exist.** `workflow_run` (after CI completes, `branches: [main]`)
  is the automatic path; `workflow_dispatch` with an optional `image_tag` input
  is the manual re-deploy / rollback path. The job's `if:` accepts a dispatch
  unconditionally, and otherwise requires
  `workflow_run.conclusion == 'success' && workflow_run.head_branch == 'main'`,
  so a mis-configured trigger can never deploy a non-`main` commit.
- **The tag is `workflow_run.head_sha`, never `github.sha`.** Under `workflow_run`,
  `github.sha` is the head of the default branch *at trigger time* — it advances as
  soon as anyone merges, so using it would silently deploy a commit CI never built.
  (The `Resolve and validate the image tag` step says this explicitly.)
- **Only SHAs that were pushed to `main` have images.** CI's build-push step is
  gated on `github.event_name == 'push' && github.ref == 'refs/heads/main'`;
  feature-branch and PR runs build but do not push.
- **Deploys are serialised.** Concurrency group `deploy-staging` with
  `cancel-in-progress: false`. A second deploy waits; a running deploy is never
  cancelled midway (which could leave the host between `up -d` and the health
  check).
- **The health check runs on the host, not on the runner.** Every middleware port
  and the backend port are bound to `127.0.0.1` on the staging host, so a GitHub
  runner probing them would only probe its own loopback. The workflow SSHes in and
  runs `docker/health-check.sh` there, which is why `curl` must be installed on
  the host.
- **The GHCR login on the host is conditional.** It runs **only** when
  `GHCR_READ_TOKEN` is set; the `roomflow-*` packages are public, so the default
  path writes no credential to the host at all (see [§2.2](#22-secrets)).
- **Nothing is passed to the host as unchecked free text.** Every value that
  reaches a remote command line is checked against an allowlist first — on the
  runner *and* again on the host, using the same shipped library — with one
  exception, `GHCR_USER`, which is refused by a targeted character rejection (a
  single quote or a line break) rather than a full allowlist, because the value
  sits inside single quotes in a remote `docker login`
  ([§2.3](#23-variables)). The three values interpolated into `~/.ssh/config`
  (`STAGING_SSH_PORT`, `STAGING_HOST`, `STAGING_USER`) are guarded against the
  config-file hazard rather than the shell one — a newline there starts a new ssh
  *directive*, not a new word ([§3](#3-ssh-deploy-key-setup)). Three inputs are
  **not** validated at all: `STAGING_SSH_PRIVATE_KEY`, `STAGING_KNOWN_HOSTS` and
  the GHCR token are inert by construction — they reach the host as a herestring, a
  `printf '%s'` into a data file, and a stdin pipe respectively, and none of them
  ever reaches a command line.

### The shared deploy library

`.github/scripts/staging-deploy-lib.sh` is a new, load-bearing artifact in this
deployment: a single bash file of functions, shipped to the host by the workflow
alongside the compose file and the health-check script, and sourced by every
remote script the workflow runs. See [§4.3](#43-the-shared-deploy-library).

### The default-branch caveat

GitHub's documented rule is that a `workflow_run` workflow **only triggers if the
workflow file exists on the default branch**. This repository's default branch is
currently **`ci/github-actions`**, not `main`. Consequences:

- Merging `deploy-staging.yml` into `main` alone is **not sufficient** for the
  automatic path to fire. CI can go green and no deploy happens — silently.
- `workflow_dispatch` is only offered for a workflow that exists on the default
  branch, so the manual rollback entry point may be unavailable too (it does work
  from the API/CLI for any ref that has the file, but the `--ref` has to name that
  ref, and any ref the file is on can be dispatched — which is exactly the hole
  the environment gate in [§2.1](#21-the-staging-github-environment-required-and-inert-until-configured) exists to close).

**This is the user's decision to make, not something this slice can fix.** Either
make `main` the default branch, or land `deploy-staging.yml` on whatever the
default branch is, and pick one canonical deploy branch.

### Relationship to PLAN.md

`PLAN.md` describes `deploy-staging` as a stage of the CI pipeline and lists an
E2E stage in CI; the implementation differs — it is a separate workflow chained by
`workflow_run`, and `ci.yml` currently has no E2E job. This document describes the
implementation.

---

## 2. Required secrets, variables and environment

The names below were extracted mechanically from the workflow
(`grep -oE 'secrets\.[A-Za-z_]+|vars\.[A-Za-z_]+'`), and the workflow reads
**nothing else**:

```text
secrets.STAGING_SSH_PRIVATE_KEY   secrets.STAGING_HOST
secrets.STAGING_USER              secrets.STAGING_KNOWN_HOSTS
secrets.GHCR_READ_TOKEN

vars.STAGING_DEPLOY_DIR           vars.STAGING_SSH_PORT
vars.STAGING_ENV_FILE             vars.STAGING_BACKEND_URL
vars.STAGING_FRONTEND_URL         vars.GHCR_USER
```

There is **no** `secrets.GITHUB_TOKEN` reference anywhere in the workflow: the
built-in token is neither used nor needed, and the deploy job does **not** request
the `packages:` permission (see [§2.5](#25-job-permissions)).

### 2.1 The `staging` GitHub Environment (required, and inert until configured)

The deploy job declares:

```yaml
environment: staging
```

This environment **does not exist yet**, and GitHub auto-creates a referenced
environment with **no protection rules** — so until you configure it, the gate
protects nothing at all.

**Required setup** (Settings → Environments → New environment → `staging`):

| Setting | Value | Why |
| --- | --- | --- |
| **Required reviewers** | at least one human | A staging deploy must be approved, not automatic. |
| **Deployment branches** | restricted to the deploy branch only (e.g. `main`) | Stops a deploy from a feature branch. |
| **Environment secrets** | move the deploy secrets here (see below) | Environment secrets are only readable by runs that satisfy the gate. |

Then move the deploy secrets from repository scope to **environment scope**:

```bash
# (create the environment first — in the UI, or:)
gh api --method PUT repos/niuyeyeplus/RoomFlow/environments/staging

# now move the four credential secrets to environment scope
gh secret set STAGING_HOST               --env staging --body "staging.example.com"
gh secret set STAGING_USER               --env staging --body "deploy"
gh secret set STAGING_SSH_PRIVATE_KEY    --env staging < ~/.ssh/roomflow-staging-deploy
gh secret set STAGING_KNOWN_HOSTS        --env staging < /tmp/staging-known-hosts-line

# only if you ever made the packages private:
gh secret set GHCR_READ_TOKEN            --env staging --body "<PAT with read:packages>"

# environment-scoped secrets are listed separately from repository ones
gh secret list --env staging
```

**Why this matters (the threat the gate closes).** Repository-scoped secrets are
readable by a workflow run on **any** branch. Without the gate, a collaborator
with push access to any branch could edit `deploy-staging.yml` on that branch,
dispatch the run, and print the deploy private key. The hole only closes once the
secrets live at **environment** scope **and** the deployment branch policy is set
— either one alone leaves it open. Protection rules can also be applied through
the environments API, but that payload is not spelled out here; the UI is the
supported path.

### 2.2 Secrets

Settings → Secrets and variables → Actions → *Secrets* (four of the five belong
at environment scope — [§2.1](#21-the-staging-github-environment-required-and-inert-until-configured)):

| Name | Required? | Must contain |
| --- | --- | --- |
| `STAGING_SSH_PRIVATE_KEY` | **yes** | Private key of the deploy user on the staging host. The workflow fails immediately if it is empty. |
| `STAGING_HOST` | **yes** | Staging host name or IP. |
| `STAGING_USER` | **yes** | SSH user on the host. Must be able to run `docker` (i.e. be in the `docker` group — the workflow never uses `sudo`) and must own/write the deploy directory. |
| `STAGING_KNOWN_HOSTS` | optional, recommended | The `known_hosts` line for the staging host. Without it the workflow falls back to `StrictHostKeyChecking=accept-new` (trust on first use) and emits a warning — see [§3](#3-ssh-deploy-key-setup). |
| `GHCR_READ_TOKEN` | optional — **not needed by default** | A PAT with `read:packages`. It is only used for `docker login ghcr.io` **on the host**, and the login step is **skipped entirely when it is empty**. The `ghcr.io/niuyeyeplus/roomflow-{backend,frontend}` packages are **public** (verified by an anonymous manifest `GET` returning HTTP 200), so the host pulls them with no credential and the default path stores nothing on the host. Set it only if the packages are ever made private. |

There is no fallback to a GitHub token: an unset `GHCR_READ_TOKEN` means "pull
anonymously", not "log in as the runner". `github.actor` is only used as the
*username* for the optional login (see `GHCR_USER` below).

### 2.3 Variables

Settings → Secrets and variables → Actions → *Variables* (all optional, all
non-secret; every one has a working default):

| Name | Default when unset | Meaning |
| --- | --- | --- |
| `STAGING_DEPLOY_DIR` | `/opt/roomflow/staging` | Deploy directory on the host. Validated against `^/[A-Za-z0-9._/-]+$` — no spaces, no shell metacharacters, and no newline. |
| `STAGING_SSH_PORT` | `22` | SSH port, written into the runner's `~/.ssh/config`. **Validated before that file is written**: refused if empty, if it holds anything but digits, if it is longer than 5 characters, if it has a leading zero, or if it falls outside 1–65535. See [§3](#3-ssh-deploy-key-setup). |
| `STAGING_ENV_FILE` | unset → auto-detect on the host | A **bare file name inside the deploy dir** (`[A-Za-z0-9._-]`), e.g. `.env.staging`. **Any `/`, `..`, `.`, or absolute path is rejected** by the pre-flight validation, before anything is shipped. When set, the named file is used as given and there is **no** fallback to another file. Leave it unset to use auto-detection. |
| `STAGING_BACKEND_URL` | **empty** → derived on the host | Optional probe-URL override. Unset, the URL is derived from `STAGING_BACKEND_PORT` in the env file (falling back to `18080`). Setting one that **names a different port** than the env file is a **hard error**, not a silent surprise. |
| `STAGING_FRONTEND_URL` | **empty** → derived on the host | As above, from `STAGING_FRONTEND_PORT` (falling back to `8081`). |
| `GHCR_USER` | `github.actor` | Username for the optional GHCR login. Only consulted when `GHCR_READ_TOKEN` is set. **Refused if it contains a single quote or a line break** — it is interpolated inside single quotes in the remote `docker login` command, and a single quote is the only way out of POSIX single-quoting. Brackets such as `dependabot[bot]` are fine. |

> **Do not "helpfully" set `STAGING_BACKEND_URL` / `STAGING_FRONTEND_URL` to the
> default `http://127.0.0.1:<port>`.** They are empty by default **on purpose**:
> the probe port is read back from the operator's env file, so an operator who
> moves `STAGING_FRONTEND_PORT` still gets a correct probe. An override that names
> a port which disagrees with a port **explicitly set in the env file** aborts the
> deploy with
> `STAGING_BACKEND_URL='…' and STAGING_BACKEND_PORT=… in <file> disagree about the port`.
> (If the env file does not set the port, there is nothing to disagree with and the
> override is used as given — the library assumes the published default.)
>
> The one legitimate use of these variables is a URL with **no port of its own** —
> e.g. `https://staging-api.example.com` in front of the loopback-bound stack.
> That cannot disagree with the file's publish port and is accepted.

### 2.4 Ready-to-paste commands

Run from a clone of this repository; add `--repo niuyeyeplus/RoomFlow` to run them
from anywhere.

```bash
# --- required secrets (environment scope; create the environment first, §2.1) ---
gh secret set STAGING_HOST            --env staging --body "staging.example.com"
gh secret set STAGING_USER            --env staging --body "deploy"
gh secret set STAGING_SSH_PRIVATE_KEY --env staging < ~/.ssh/roomflow-staging-deploy

# --- optional but recommended ---
gh secret set STAGING_KNOWN_HOSTS     --env staging < /tmp/staging-known-hosts-line

# --- only if the GHCR packages are ever made private (they are public today) ---
gh secret set GHCR_READ_TOKEN         --env staging --body "<PAT with read:packages>"

# --- optional repository variables: set ONLY the ones you need to change ---
gh variable set STAGING_DEPLOY_DIR --body "/opt/roomflow/staging"
# gh variable set STAGING_SSH_PORT   --body "2222"        # only for a non-default port
# gh variable set STAGING_ENV_FILE   --body ".env.staging" # only if you renamed the file

# LEAVE THESE UNSET unless the API/frontend is reachable at a different URL with
# no port of its own. Setting them to the loopback defaults is a hard error:
# gh variable set STAGING_BACKEND_URL  --body "http://127.0.0.1:18080"
# gh variable set STAGING_FRONTEND_URL --body "http://127.0.0.1:8081"

# --- inspect what is configured ---
gh secret list --env staging
gh variable list
```

`gh secret set NAME` without `--body` reads the value from stdin, which is the
right form for the key: the private key never appears in the shell history or in
the process list.

Note that the **GHCR credential is used on the host**, and when it is used the
`docker login` stores it in the host's docker config (`~/.docker/config.json`) in
base64 — not encrypted. With `GHCR_READ_TOKEN` unset (the default) the login step
is skipped and nothing is written. If you do set it, revoke or rotate the PAT if
the host is ever decommissioned.

### 2.5 Job permissions

```yaml
permissions:
  contents: read
```

That is the whole permission block. Earlier revisions granted `packages: read` for
a `secrets.GITHUB_TOKEN` fallback; **both the fallback and the scope are gone**.
GHCR access happens on the staging host and uses no GitHub credential by default,
so there is nothing left to grant it for — least privilege.

---

## 3. SSH deploy key setup

### Path contract

```text
~/.ssh/roomflow-staging-deploy        # private key  (never leaves your machine + the GH secret)
~/.ssh/roomflow-staging-deploy.pub    # public key   (goes into the host's authorized_keys)
```

The workflow does not read a key path from configuration; it writes the
`STAGING_SSH_PRIVATE_KEY` secret to `~/.ssh/roomflow_staging_deploy` **on the
GitHub runner** and points `IdentityFile` at it. That runner-side name is internal
to the workflow, so your local filename is a free choice — the two do not have to
match, and no secret or variable refers to the local path. (If you do use a
different local name, keep the `.pub` next to it.)

### 1. Generate the keypair

```bash
ssh-keygen -t ed25519 -C "roomflow-staging-deploy" -f ~/.ssh/roomflow-staging-deploy
```

**Passphrase trade-off.** The runner's SSH config sets `BatchMode yes`, which
disables interactive prompts precisely so a prompt cannot hang the job until the
job timeout — and there is no `ssh-agent` step in the workflow. A passphrase
protected key would therefore simply fail to authenticate in CI. The practical
choice for this pipeline is an **empty passphrase** (press Enter), which makes the
secret file a bearer credential: it is only as safe as the GitHub Secret and the
runner. Mitigate on the host side instead: use a dedicated deploy user with no
`sudo`, and optionally restrict the key in `authorized_keys` (see below).
If you need a passphrase, you must first add an agent step to the workflow — at
which point the passphrase also has to live in a secret, which is a wash.

### 2. Install the public key on the staging host

```bash
# Simplest, if ssh-copy-id is available:
ssh-copy-id -i ~/.ssh/roomflow-staging-deploy.pub -p 22 deploy@staging.example.com

# Explicit equivalent (works anywhere):
cat ~/.ssh/roomflow-staging-deploy.pub | ssh -p 22 deploy@staging.example.com \
  'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
```

Then confirm the key is the one being used, with no password fallback:

```bash
ssh -i ~/.ssh/roomflow-staging-deploy -o IdentitiesOnly=yes -o BatchMode=yes \
  -p 22 deploy@staging.example.com 'docker version --format "{{.Server.Version}}"; id'
```

Optional hardening in `authorized_keys` (prefix the key line) — restricting the
key is the cheapest compensation for an unencrypted private key:

```text
from="<your-runner-egress-IPs>",no-agent-forwarding,no-port-forwarding,no-pty ssh-ed25519 AAAA... roomflow-staging-deploy
```

`from=` with GitHub-hosted runners is awkward in practice (the egress ranges are
large and change), so most deployments skip it; `no-pty` / `no-agent-forwarding`
cost nothing.

### 3. Load the private key into the GitHub Secret

```bash
# stdin form (recommended: the value is never an argument).
# --env staging is the scope this secret belongs at (see §2.1):
gh secret set STAGING_SSH_PRIVATE_KEY --env staging < ~/.ssh/roomflow-staging-deploy

# --body form, if you prefer it explicit
gh secret set STAGING_SSH_PRIVATE_KEY --env staging --body "$(cat ~/.ssh/roomflow-staging-deploy)"

# verify it now exists (its value is never readable again)
gh secret list --env staging
```

Do not paste the key into a chat, an issue, a log or a commit. Note that the
workflow stores it with `install -m 600 /dev/stdin`, so it is created already
`0600` — there is no window in which it is world-readable, and the step
deliberately avoids `set -x`.

> **The private key must NEVER be committed to this repository.** `.gitignore`
> covers `*.pem` and `*.key` but *not* an SSH key under a custom name, and CI runs
> gitleaks as part of the `security-scan` job — a committed private key would both
> leak and turn CI red. Keep the keypair outside the working tree (the `~/.ssh`
> path above does that by construction).

### 4. Host-key verification — what the workflow actually does

The `Configure SSH for the staging host` step branches on `STAGING_KNOWN_HOSTS`:

| `STAGING_KNOWN_HOSTS` | Resulting `StrictHostKeyChecking` | Trade-off |
| --- | --- | --- |
| set | `yes` | The host key is pinned in `~/.ssh/known_hosts`; a MITM or DNS hijack fails loudly. **Use this.** |
| unset | `accept-new` | Trust on first use: the key is recorded on first contact and later mismatches fail, but the **first** connection could be intercepted. The workflow logs a `::warning::` about it. Acceptable while bootstrapping, not for a host reachable from untrusted networks. |

Generate the secret's contents from the host, using the same port you configured
in `STAGING_SSH_PORT` (a non-default port changes the line to `[host]:port`):

```bash
ssh-keyscan -p 22 -t ed25519,ecdsa,rsa staging.example.com > /tmp/staging-known-hosts-line
cat /tmp/staging-known-hosts-line     # sanity-check before storing
gh secret set STAGING_KNOWN_HOSTS --env staging < /tmp/staging-known-hosts-line
# -t is deliberate: ssh-keyscan's defaults may not return every host key type,
# and a pin missing the type sshd prefers fails strict checking with
# "No ED25519 host key is known for [host]:port" (seen on the first deploy).
```

`ssh-keyscan` output is unauthenticated — verify the fingerprint out of band
(compare it with what the host itself reports, or with what your first manual
`ssh` stored) before trusting it. If the host is rebuilt, its host key changes and
a pinned `known_hosts` will make every deploy fail until you refresh the secret;
that failure is the feature.

The runner-side SSH config the step writes is:

```text
Host roomflow-staging
  HostName <STAGING_HOST>
  User <STAGING_USER>
  Port <STAGING_SSH_PORT or 22>
  IdentityFile ~/.ssh/roomflow_staging_deploy
  IdentitiesOnly yes
  StrictHostKeyChecking <yes|accept-new>
  UserKnownHostsFile ~/.ssh/known_hosts
  BatchMode yes
  ConnectTimeout 15
  ServerAliveInterval 30
```

### Why those three values are checked before the file is written

`STAGING_SSH_PORT`, `STAGING_HOST` and `STAGING_USER` are interpolated into that
file, and the heredoc that writes it is deliberately **unquoted** (it has to
interpolate). A value containing a newline therefore does not produce a malformed
value — it produces a **new directive**, because `~/.ssh/config` is a config file,
not a shell: `22\n  ProxyCommand sh -c '…'` is not a rejected port, it is a port
plus a directive. `ProxyCommand` is the directive that matters: it needs no
`PermitLocalCommand`, it ignores `BatchMode`, and it runs on the **runner** — the
machine holding the deploy private key — on *every* later `ssh`/`scp` call. So no
value is written before it has been checked, and the checks run before the file is
created, so a rejected value cannot leave a usable config behind.

| Value | Rule | Why this rule |
| --- | --- | --- |
| `STAGING_SSH_PORT` | refused if **empty**; if it contains **anything but digits**; if it is **longer than 5 characters**; if it has a **leading zero**; or if it falls outside **1–65535** | A port has an exact shape, so a strict rule costs nothing. The empty arm is **defense in depth and unreachable as the workflow stands**: an empty string is falsy in a GitHub expression, so the `\|\| '22'` default in `env:` collapses a set-but-empty variable to `22` exactly as it does an unset one. The arm is kept because it is the only guard against an empty port if that default is ever dropped, and an empty `Port` line is refused by ssh with a bare "Bad port" — so naming the failure explicitly is worth keeping. A leading zero is refused rather than normalised: `[ 08080 -lt 1 ]` is not a decimal comparison (it returns false, so the range test would let it through), and ssh, Compose and `docker` need not agree on what `08080` publishes. |
| `STAGING_HOST` | refused if it contains a **line break anywhere**, or **leading/trailing whitespace** (including a whitespace-only value) | A strict allowlist could reject a legitimate value — an IPv6 literal, a `user@host`-style name — so only what can *never* be legitimate is refused. Both patterns are invisible in the GitHub UI and would otherwise be pasted in silently. |
| `STAGING_USER` | as `STAGING_HOST` | Same reasoning: a user name has no shape this workflow can safely impose, but it can never legitimately contain a line break or lead/trail with whitespace. |

The checks reject rather than repair, and they are **fail-closed**: a stray `"`,
`'` or internal space inside `STAGING_HOST`/`STAGING_USER` leaves the whole config
file unparseable, and ssh rejects the **entire** file — so every later `ssh`/`scp`
call fails loudly instead of quietly connecting somewhere unintended. The arm that
catches a newline deliberately does **not** echo the offending value, because that
value is exactly the kind of payload that could inject a workflow command into the
log. Quoting the values inside the heredoc is *not* relied on: `ssh_config` has no
continuation syntax, so a newline still ends the directive line, and quoting only
helps if every interpolated value is quoted — a version-dependent parser
behaviour, not a documented guarantee. Once these checks have run, no interpolated
value can contain a newline, so quoting would add nothing.

`GHCR_USER` is the one value that reaches a remote command line without a full
allowlist. It is interpolated inside single quotes in the remote `docker login`
command, so it is refused if it contains a **single quote** (the only way out of
POSIX single-quoting) or a **line break** (which would end the command) — rather
than trusting the quoting. It defaults to `github.actor`, and brackets such as
`dependabot[bot]` are legitimate and accepted.

`STAGING_SSH_PRIVATE_KEY`, `STAGING_KNOWN_HOSTS` and the GHCR token are not
validated at all, and do not need to be: they arrive as a herestring to `install`,
a `printf '%s'` into a data file, and a stdin pipe respectively, so none of them is
ever parsed as a command line. See [§10](#10-known-limitations) for what was and was
not executed.

---

## 4. Staging host preparation

Everything in this section must exist **before the first deploy**. The workflow
creates none of it except the deploy directory itself (`mkdir -p`).

### 4.1 Software prerequisites

- **Docker Engine** with the **Compose v2 plugin** (`docker compose ...`, not the
  legacy `docker-compose`). The deploy runs as the SSH user without `sudo`, so
  that user must be in the `docker` group.
- **`curl`** on the host — `docker/health-check.sh` aborts with exit code 2 if it
  is missing. Every middleware port is loopback-bound, so the check cannot be
  moved to the runner.
- **`bash`** (the shipped library and remote scripts are bash, and the workflow
  invokes the remote side as `bash -s`).
- **`sshd`** and network access from the host to `ghcr.io`.

```bash
# verify on the host (expected: clean answers)
docker --version
docker compose version
curl --version | head -1
```

The compose file deliberately avoids the `required: false` `env_file` long syntax
(Compose ≥ v2.24), so older v2 releases are supported; the exact minimum version
was not tested.

### 4.2 Deploy directory

Default `/opt/roomflow/staging` (override with the `STAGING_DEPLOY_DIR` variable,
which must match `^/[A-Za-z0-9._/-]+$`). The workflow does
`ssh roomflow-staging mkdir -p <dir>` and then ships **three files** into it, so
the deploy user needs write access — creating the directory up front as that user
is the simplest way to guarantee it:

```bash
sudo mkdir -p /opt/roomflow/staging
sudo chown "$USER":"$USER" /opt/roomflow/staging
```

After a deploy the directory contains exactly what the workflow shipped, plus your
env file:

```text
/opt/roomflow/staging/
├── docker-compose.staging.yml   # shipped from the deployed revision, overwritten each deploy
├── health-check.sh              # shipped from the deployed revision, overwritten each deploy
├── staging-deploy-lib.sh        # shipped from the deployed revision, overwritten each deploy
└── .env.staging                 # YOURS — the workflow never creates or overwrites it
```

The on-host layout is flatter than the repository's: there is no `docker/`
subdirectory on the host, and the workflow runs
`bash "${DEPLOY_DIR}/health-check.sh" "<backend-url>" "<frontend-url>"` — it does
**not** `cd` into the deploy dir, and it passes the URLs as positional arguments.

### 4.3 The shared deploy library

`staging-deploy-lib.sh` is a plain bash file of functions with no side effects, and
it is the **single source of truth** for three things the deploy, rollback, health
check and diagnostics all need:

- newline-safe validation of the values handed to a remote command line
  (`validate_deploy_dir`, `validate_image_tag`, `validate_env_override`,
  `validate_probe_url`);
- the env-file resolution rules (`resolve_env_file`, `staging_context`) and the
  required-variable pre-flight (`require_env_vars`);
- probe-URL resolution from the env file's port variables (`resolve_probe_url`).

**Why it exists.** Those rules used to be copy-pasted into each remote script and
had *already diverged*: only the deploy copy ran the required-variable scan, so a
rollback could start against an env file the deploy would have rejected and
recreate the very breakage that triggered it. One shipped copy removes that class
of bug, and because the runner sources the same file for its pre-flight
validation, the runner and the host cannot disagree about what is acceptable.

**How it is used.** The workflow `scp`s it into the deploy dir, and every remote
script begins with `. "${DEPLOY_DIR}/staging-deploy-lib.sh"`. If it is missing,
each step degrades in its own documented way:

| Step | Behaviour without the library |
| --- | --- |
| Deploy | exits 1 with `cannot load the deploy library` — `compose up` never runs, so nothing on the host is changed. |
| Health check | exits 2 → reported as `config_error`; **no rollback** (the host is misconfigured, the deploy is fine). |
| Rollback | exits 1 → reported as `not_attempted`; the rollback changed nothing. |
| Diagnostics | prints `deploy library unavailable` and skips — diagnostics never fail the job. |

The runner-side `Validate deployment configuration`, `Capture the currently
deployed tag` and diagnostics steps source it from the checked-out revision; the
`Verify the deploy artifacts exist` step fails the run early if any of the three
files is absent from the revision being deployed.

> **The manual on-host commands in [§6](#direct-on-host-fallback) and
> [§7](#the-health-check-script) do _not_ need the library.** They invoke
> `docker compose` and `health-check.sh` directly, and `health-check.sh` is
> self-contained. But if you paste a *workflow* remote snippet onto the host by
> hand, you must ship the library too — the snippets source it as their first
> statement.

### 4.4 The env file

The env file is **operator-owned**. The workflow never creates, writes or
overwrites it, and refuses to deploy without it.

**Resolution order** (identical in every step, because it is the one library
function they all call):

1. `STAGING_ENV_FILE` if the variable is set — a **bare file name inside the
   deploy dir**, used as given. A value containing `/`, `..`, `.` alone, or a shell
   metacharacter is **rejected by the pre-flight validation before anything is
   shipped** (`STAGING_ENV_FILE must be a bare file name inside the deploy dir`).
   When set, there is **no** silent fallback to a different file: naming a file
   that does not exist is an error, not a prompt to use another one.
2. `<deploy dir>/.env.staging` if it exists — **the primary name**, and the one
   every other staging artifact documents (the compose header,
   `.env.staging.example`, `docker/README.md`, this document).
3. `<deploy dir>/.env` if it exists.
4. Otherwise: **the deploy fails** with an actionable error that names **both**
   candidates in resolution order, the override, and the `cp` command to fix it.

> `.env.staging` is probed **first** on purpose. The dev stack's own docs tell
> operators to `cp .env.example .env`, so a stale root `.env` sitting in the deploy
> directory is entirely realistic — and probing `.env` first used to let it shadow
> a perfectly valid `.env.staging` and abort the deploy.

Preparation — copy the tracked template to the host and fill it in. The template
lives at the **repository root**, so bring it from your clone; note the workflow
only ships the three files listed in [§4.2](#42-deploy-directory), so a host that
has never seen this repo has no template to copy from:

```bash
# run from your local clone, on your machine
scp -P 22 .env.staging.example deploy@staging.example.com:/tmp/.env.staging.example
ssh -p 22 deploy@staging.example.com \
  'install -m 600 /tmp/.env.staging.example /opt/roomflow/staging/.env.staging && rm /tmp/.env.staging.example'
```

Then edit `/opt/roomflow/staging/.env.staging` on the host and replace **every**
`CHANGE_ME`. Keep it `0600` — it holds real credentials.

> **Warning:** a missing or incomplete env file fails the deploy. Two distinct
> failure modes are caught: the file is absent (hard error before Compose is
> invoked), or the file exists but a required value is empty (the library derives
> the required names from the compose file's own `${VAR:?}` guards and refuses to
> deploy, printing the names — never the values). Without that guard Compose would
> interpolate `${VAR}` to an empty string and the stack would come up broken.
>
> Two further checks run over the same file:
>
> - **`export VAR=value` is accepted**, the last assignment wins (as in Compose),
>   and a CRLF file behaves like an LF one.
> - **A value that is explicitly empty counts as missing** — `VAR=`, `VAR=""` and
>   `VAR=''` are all rejected, because Compose interpolates all three to an empty
>   string.

### 4.5 Startup-critical variables

The required set is read from the compose file's own `${VAR:?}` guards, so it
stays correct if the stack changes. Today it is exactly these eight names — a
non-empty, interpolation-safe value for each is mandatory:

| Variable | What it feeds | If it is missing/empty |
| --- | --- | --- |
| `DB_PASSWORD` | MySQL user password + backend datasource | MySQL refuses to initialise; Compose aborts (`${VAR:?}`) |
| `MYSQL_ROOT_PASSWORD` | MySQL root | MySQL 8.4 refuses to initialise with an empty root password |
| `REDIS_PASSWORD` | `redis-server --requirepass` | The redis service re-checks the value it received and refuses to start rather than come up with no authentication |
| `RABBITMQ_USERNAME` | RabbitMQ default user | RabbitMQ has no usable user |
| `RABBITMQ_PASSWORD` | RabbitMQ default password | as above |
| `JWT_SECRET` | backend token signing key (`application-staging.yml` resolves `${ROOMFLOW_JWT_SECRET:${JWT_SECRET}}`; the compose passes `JWT_SECRET` only) | backend fails to start on a too-short HMAC key |
| `ADMIN_PASSWORD_HASH` | Flyway placeholder `admin-password-hash` in `V2__seed_rooms.sql` | **backend dies during startup, before `/actuator/health` ever answers → the container restart-loops** |
| `CORS_ALLOWED_ORIGINS` | `app.cors.allowed-origins` in the backend | not startup-fatal (the app would default it to empty = no cross-origin requests), guarded anyway so no deploy silently ships a stack whose browsers reject the SPA's API calls |

`IMAGE_TAG` is also `${IMAGE_TAG:?...}`-guarded in the compose, but the workflow
always supplies it in the shell environment (which takes precedence over the env
file), so it is exempt from the emptiness check. It still must be set for any
*manual* `docker compose` run on the host — a file left at the template's
`IMAGE_TAG=CHANGE_ME_git_sha` will make a manual run try to pull a nonexistent tag.

**`ADMIN_PASSWORD_HASH` is the one most likely to be missed.** The dev compose
does not pass it at all, so copying a dev `.env` into this stack is the single
most likely way to break staging. It must be a **BCrypt hash generated offline**,
never a plaintext password, and never generated on the staging host from a secret
that then gets logged. It is used to seed the initial `admin` account
(`V2__seed_rooms.sql`) in the staging database only.

```bash
# Option A — htpasswd (apache2-utils). The sed rewrites the $2y$ prefix to $2a$,
# which is the variant Spring Security's BCryptPasswordEncoder expects.
htpasswd -bnBC 10 "" 'the-chosen-password' | tr -d ':\n' | sed 's/^\$2y/\$2a/'

# Option B — python-bcrypt  (pip install bcrypt)
python -c "import bcrypt; print(bcrypt.hashpw(b'the-chosen-password', bcrypt.gensalt(10)).decode())"
```

Either command prints a value that is **60 characters** in total: a
`$2a$`/`$2b$`/`$2x$`/`$2y$` prefix, a two-digit cost, another `$`, then 53
characters of `[A-Za-z0-9./]`. (That exact shape is what the deploy library uses to
decide whether a `$`-bearing value is a BCrypt hash it should warn about, or a
genuine typo it must reject — see below.)

#### Writing `ADMIN_PASSWORD_HASH` so Compose does not mangle it

Compose applies its own interpolation to env-file values, and a BCrypt hash is
made of `$` characters — so a bare hash can reach the container mangled. The
consequences run all the way through the pipeline: the Flyway placeholder no
longer matches, the backend dies during startup, `/actuator/health` never answers,
the health check fails, and the deploy **rolls back**.

**Do this — single-quote the whole value:**

```text
ADMIN_PASSWORD_HASH='$2a$10$...the 53 characters your generator printed...'
```

Compose takes a **single-quoted** env-file value **verbatim** (no interpolation),
so this form is correct whether or not your Compose version re-interpolates env
files. It is the form the deploy library recommends in its own warning message and
the only one whose meaning does not depend on the Compose version.

**The alternatives, in order of decreasing preference:**

1. **Escape every `$` as `$$`**: `ADMIN_PASSWORD_HASH=$$2a$$10$$...`. This still
   works, but it is the **version-dependent** option — whether the escape is
   needed and how it is applied depends on the Compose version's interpolation
   order, which is why it is no longer the primary instruction.
2. **Export it from the shell environment** instead of the file (see the
   `.env.staging.example` header): an exported value is already interpolated by the
   shell and is never re-expanded by Compose. Note the workflow's `compose up` runs
   over SSH with only `IMAGE_TAG` set in the environment, so this option is for
   *manual* runs on the host, not for what the pipeline deploys.

**What the workflow does about it.** The pre-flight `require_env_vars` check
distinguishes three cases:

| Value in the env file | Result |
| --- | --- |
| Single-quoted BCrypt hash | Accepted silently. |
| Unquoted / double-quoted BCrypt hash (exactly `$2[abxy]$NN$` + 53 chars) | **`::warning::` only — the deploy PROCEEDS.** Compose's treatment of an unescaped `$` is not the same on every version, and only the running backend can confirm the mangle; the health check plus rollback is the mechanism for that. |
| Any **other** value containing a literal `$` | **Hard failure** (exit 1, names only): Compose would expand it, so the container would receive an empty secret — an empty JWT signing key, an empty DB password. |

The warning-not-failure choice means a mistakenly-bare hash shows up as a **failed
health check plus a rollback**, not as a pre-flight stop — so if the health check
fails for a revision whose only difference is this variable, check the hash's
quoting first.

- **The seed runs once.** Flyway applies `V2__seed_rooms.sql` a single time. On a
  host whose staging database volume already exists, changing
  `ADMIN_PASSWORD_HASH` in the env file does **not** update the existing `admin`
  row — the placeholder only affects the initial insert. Changing it later means
  updating the row directly (and is out of scope for this document).

### 4.6 First-deploy checklist

```text
[ ] staging host provisioned (open question — see §10)
[ ] deploy user exists, in the docker group, key installed in authorized_keys
[ ] docker + compose v2 plugin + curl + bash installed and verified
[ ] deploy directory exists and is writable by the deploy user
[ ] /opt/roomflow/staging/.env.staging created from the template, every CHANGE_ME replaced
[ ] ADMIN_PASSWORD_HASH holds a BCrypt hash, SINGLE-QUOTED as '$2a$10$...'
[ ] CORS_ALLOWED_ORIGINS lists the exact scheme+host+port the browser will use
[ ] secrets set at ENVIRONMENT scope and variables set (§2), STAGING_KNOWN_HOSTS included
[ ] the `staging` environment has required reviewers + a deployment branch policy (§2.1)
[ ] the workflow file exists on the repository's DEFAULT branch (§1)
[ ] images exist in GHCR for the SHA you intend to deploy (§5)
```

---

## 5. Deploy flow

### Normal path

1. A PR is reviewed and merged into `main` (branch convention: one slice, one
   branch, one PR; manual review before merge).
2. CI runs on the resulting `main` push. `docker-build-push` pushes
   `ghcr.io/niuyeyeplus/roomflow-backend:<full sha>` and
   `ghcr.io/niuyeyeplus/roomflow-frontend:<full sha>` on a `push` to `main`;
   a feature-branch or PR run builds but does not push.
3. CI completes → **Deploy Staging** starts automatically (`workflow_run`,
   `branches: [main]`, only on `conclusion == 'success'`), subject to the
   environment gate and the default-branch caveat in [§1](#1-overview).
4. The job:
   - resolves the tag (`workflow_run.head_sha`), validates it in two gates, and
     checks out **that exact revision**;
   - verifies all three deploy artifacts exist in it;
   - runs `Validate deployment configuration` (deploy dir, `STAGING_ENV_FILE`,
     probe URLs) using the shipped library;
   - configures SSH, captures the currently deployed tag as the rollback target;
   - ships the compose file, `health-check.sh` and `staging-deploy-lib.sh`;
   - logs in to GHCR on the host **only if `GHCR_READ_TOKEN` is set**;
   - runs `docker compose up -d` with `IMAGE_TAG=<sha>` and the operator's env
     file, then runs the health check on the host.
5. Green job = the tag is live and healthy. Red job = either the deploy failed, or
   the health check failed (a rollback may still have succeeded — see §6).

### Watching it

```bash
# what the deploy workflow has done
gh run list --workflow=deploy-staging.yml --limit 10
gh run list --workflow=CI --branch main --limit 5

# follow a specific run to completion, non-zero exit if it fails
gh run watch <run-id> --exit-status

# step-by-step logs for a finished (or running) deploy
gh run view <run-id>
gh run view <run-id> --log

# last resort: re-run a failed deploy of the same revision
gh run rerun <run-id>
```

**Timing expectations, from the workflow.** The job timeout is **55 minutes**, on
purpose strictly greater than the sum of every step timeout (52 minutes), so the
job timeout can never fire while the rollback step is still running. The step
budget is:

| Group | Steps | Minutes |
| --- | --- | --- |
| setup | resolve tag 2 + checkout 2 + verify artifacts 2 + validate config 2 + configure SSH 2 + capture previous tag 2 + ship artifacts 3 | 15 |
| GHCR login (conditional) | | 2 |
| deploy | `docker compose up -d` | 10 |
| health check | | 8 |
| rollback | | 10 |
| diagnostics | | 5 |
| report | | 2 |
| **total** | | **52** |

They are ordered this way on purpose so that a hang in the deploy step trips its
own timeout and the rollback step still gets a chance to run, instead of the job
timeout killing everything. Only one staging deploy runs at a time (concurrency
group `deploy-staging`); a queued one waits rather than cancelling the running one.

### What the job summary reports

The final `Report deploy result` step (runs `always()`) writes a Markdown table to
the run's summary:

| Item | Value |
| --- | --- |
| Resolved image tag | the validated 40-char SHA |
| Tag source | `workflow_run.head_sha` or the `workflow_dispatch` input / `github.sha` |
| Previous (rollback) tag | the captured previous SHA, or *none detected (first deploy, or non-SHA tag)* |
| `docker compose up -d` | step outcome (with the remote exit code) |
| Health check (on host) | `healthy` / `unhealthy` / `config_error` / `unknown` |
| Rollback | see the list below |
| Run | link to the run |

The Rollback row reports **what the rollback achieved**, not merely whether the
step ran:

| Value | Meaning |
| --- | --- |
| `not attempted (the deploy did not fail and the health check passed)` | the happy path |
| `yes - previous version <sha> is healthy again` | rollback performed and verified |
| `attempted, but the previous version is **not healthy**` | rolled back, but the restored stack does not pass |
| `attempted, but docker compose up -d failed - staging is in an **unknown** state` | the rollback itself failed |
| `attempted; the restored stack could not be verified (health-check.sh exited 2) - treat the state as **unknown**` | rollback ran, verification could not |
| `**not performed** - the rollback pre-flight rejected the configuration, so nothing on the host was changed by it` | rollback declined safely |
| `**impossible - no previous tag was captured; staging is left on the failed version**` | first deploy failed, or the running image was not a SHA; manual intervention required |

The step then sets the job's status: a deploy step that did not succeed, or a
health check that did not pass, **always** fails the job — including when the
automatic rollback itself succeeded. A red "Deploy Staging" run therefore does not
always mean staging is broken; read the Rollback row.

The commit SHA is deliberately not masked with `::add-mask::` — it is public
information, and masking it would replace it with `***` in the summary operators
rely on.

The deploy job's diagnostics step (only on a deploy/health/rollback failure) runs
compose `ps` and `logs --tail=200 --no-color` on the host, passing the same
mandatory `--project-directory` / `--env-file` / `-f` flags the rest of the
workflow uses (`docker compose --project-directory … --env-file … -f … ps`).
It deliberately never `cat`s the host env file and never runs `docker compose
config`, which would print the interpolated secrets. It also re-applies the
allowlist before handing any value to `ssh`, and skips host diagnostics entirely
when the library is not on the runner (i.e. when the deploy never ran).

### How values reach the host safely

`ssh host cmd a b c` does **not** exec argv remotely: ssh joins its arguments into
one string handed to the remote login shell, which re-parses it. A value
containing `;`, `$( )`, a space or a newline would therefore become remote shell
syntax. Rather than quoting, the workflow requires every value that reaches a
remote command line to match a strict allowlist first — with a single exception,
noted below — and re-checks it on the host:

```text
deploy dir  ^/[A-Za-z0-9._/-]+$
image tag   ^[0-9a-f]{40}$
env file    bare name [A-Za-z0-9._-], no '/' and no '..'
probe URL   ^https?://[A-Za-z0-9._:/-]+$
```

`GHCR_USER` is the one exception: it is a GitHub username, so any allowlist narrow
enough to be worth having would reject a legitimate value. It is instead
interpolated **inside single quotes** and refused only if it contains a single
quote or a line break ([§3](#3-ssh-deploy-key-setup)). The three values written
into the runner's `~/.ssh/config` (`STAGING_SSH_PORT`, `STAGING_HOST`,
`STAGING_USER`) never reach a remote command line, and follow the config-file model
described in [§3](#3-ssh-deploy-key-setup) instead.

The checks are **newline-safe**: they are `case` globs, not
`printf '%s' "$v" | grep -Eq '^...$'`. A glob can only match a newline if the
pattern names one, so a multi-line value always lands in the reject arm — where
the old `grep` idiom accepted any value with *one* matching line.

---

## 6. Rollback

There are three mechanisms, and they are different things.

### Automatic rollback

1. **Before anything on the host is changed**, the job captures the rollback target
   by inspecting the running container:
   `docker inspect --format '{{.Config.Image}}' roomflow-staging-backend` →
   `ghcr.io/niuyeyeplus/roomflow-backend:<tag>` → `<tag>`.
   On a first deploy no container exists, the inspect fails, and the run records
   "no rollback target".
2. The captured tag is validated like any other: it must be a full 40-char hex
   SHA. A `latest`-style or digest-pinned value is rejected with a warning, because
   neither is an immutable rollback target — automatic rollback is then simply
   **unavailable** for the run.
3. The rollback step runs when **all** of these hold: `always()`, a previous tag was
   captured, **and** at least one of:

   | Condition | Why it is in the list |
   | --- | --- |
   | the deploy step **failed** and did not report remote exit `1` (this includes a step that died before writing its exit code at all) | `compose up` may have run, so the stack may be half-recreated |
   | the deploy step was **cancelled** | same |
   | the health-check step **failed without producing a `result` output** | it was killed by its own timeout, so nothing was verified — "no verification happened" is the same situation as `unknown` |
   | the health check reported **`unhealthy`** | the deploy did not pass |
   | the health check reported **`unknown`** | the health check did not return a recognised code |

   Two cases are deliberately **excluded**:

   - **Deploy remote exit 1** — the remote script failed in its pre-flight
     (unusable env file, invalid tag) *before* `compose up`, so nothing on the host
     was changed.
   - **`config_error`** — `health-check.sh` exited `2`, i.e. the **host** is
     misconfigured (no `curl`, a bad tuning value, a probe URL that disagrees with
     the env file's port). That is not a bad deploy, and rolling a healthy stack
     back for it would destroy a working deployment.

   The deploy-outcome arms exist because the health-check step has no `if:` of its
   own, so it is **skipped** when the deploy step fails — keying the rollback only
   on the health-check step left a failed `compose up` with no recovery at all.
4. The rollback re-deploys the previous tag with the same
   `docker compose --project-directory … --env-file … -f … up -d`, running the
   **same pre-flight from the same library** (so it cannot start against an env
   file the deploy would have rejected and recreate the breakage that triggered
   it), then re-runs `health-check.sh` against it.
5. The rollback's exit code is mapped to the summary value:

   | Remote exit | Summary | Meaning |
   | --- | --- | --- |
   | `0` | `healthy` | the previous tag is healthy again |
   | `1` | `not_attempted` | the pre-flight rejected the configuration; nothing was changed |
   | `2` | `compose_failed` | the rollback ran and `compose up` failed |
   | `3` | `inconclusive` | the rollback ran; verification could not (`health-check.sh` exit 2) |
   | `4` | `unhealthy` | the rollback ran and the stack is still not healthy |
   | other | `unknown` | unmapped |

6. **The job still fails**, whatever the rollback achieved. That is intentional: a
   deploy whose verification failed must never look successful. The failure
   messages distinguish rollback impossible (staging left on the unhealthy
   version — manual intervention), rollback performed and healthy (staging is
   serving the older SHA — investigate before redeploying), rollback performed but
   not healthy (unknown state — manual intervention), and `config_error` (nothing
   was rolled back on purpose; fix the host and re-run).

`cancel-in-progress: false` on the concurrency group matters here: a cancelled
deploy could be interrupted between `up -d` and the health check, so the workflow
never cancels one.

### Manual rollback (deliberate)

Dispatch the same workflow with an older, known-good, **full 40-char** SHA:

```bash
# 1. find a candidate SHA
git fetch origin main
git log --format='%H %s' -10 origin/main

#    or ask GHCR which tags actually exist (full 40-char SHAs are the usable ones):
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:niuyeyeplus/roomflow-backend:pull&service=ghcr.io" \
  | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -H "Authorization: Bearer $TOKEN" \
  https://ghcr.io/v2/niuyeyeplus/roomflow-backend/tags/list
#    (the packages are public, so this works without a credential;
#     the GitHub UI page works too:
#     https://github.com/niuyeyeplus/RoomFlow/pkgs/container/roomflow-backend)

# 2. dispatch with an explicit tag, then watch it
gh workflow run "Deploy Staging" --ref main -f image_tag=<40-char-previous-sha>
gh run watch "$(gh run list --workflow=deploy-staging.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```

Input contract: `image_tag` is optional (`type: string`, default empty). Empty means
"deploy the head of the dispatched ref". The value is validated in two gates —
first a command-injection guard (`case`-based: non-empty, `[0-9a-f]` only, 7-40
characters) and then immutability (exactly 40 characters; CI tags images with the
full SHA, and a short prefix can be neither checked out nor pulled). A short SHA
fails immediately with a clear message; it is never silently expanded.

> **`gh workflow run` requires the workflow file to exist on `--ref`.** If it is
> not on `main`, dispatch against whichever branch has it — but note that the
> "Run workflow" button in the GitHub UI is only offered once the file is on the
> **default branch**, which is currently `ci/github-actions`
> ([§1](#1-overview)). The manual rollback entry point can therefore be
> temporarily unavailable; that is one more reason to resolve the default-branch
> question before relying on this pipeline.

A rollback is a re-deploy, not a data rollback: schema and data are whatever the
database volume already holds. Flyway migrations are forward-only here, so if the
new version applied a migration the old version cannot run against, the rollback
will start and then fail — the container will be unhealthy and the run will report
`attempted, but the previous version is not healthy`.

### Direct-on-host fallback

When GitHub Actions itself cannot be used (outage, expired credentials, a
compromised workflow), roll back on the host. This is the same **command shape**
the workflow runs (the workflow runs it from a heredoc over SSH):

```bash
ssh -p 22 deploy@staging.example.com
cd /opt/roomflow/staging

# which tag is running now
docker inspect --format '{{.Config.Image}}' roomflow-staging-backend

# re-deploy a known-good tag (host-side tooling only, no library involved).
# GHCR login state persists in ~/.docker/config.json, and the packages are public,
# so a pull normally needs no credential.
IMAGE_TAG=<40-char-known-good-sha> docker compose \
  --project-directory /opt/roomflow/staging \
  --env-file /opt/roomflow/staging/.env.staging \
  -f /opt/roomflow/staging/docker-compose.staging.yml \
  up -d

# verify the same way the workflow does
bash /opt/roomflow/staging/health-check.sh http://127.0.0.1:18080 http://127.0.0.1:8081
echo "exit=$?"
```

`--project-directory` and `--env-file` are **mandatory** here for the same reason
the workflow passes them: without them Compose resolves `${VAR}` from the
directory of the first `-f` file and silently interpolates empty strings.

These commands are safe to run even if `staging-deploy-lib.sh` was never shipped
to the host: `health-check.sh` is self-contained and does not source the library.
Only the workflow's own remote snippets do (see
[§4.3](#43-the-shared-deploy-library)).

---

## 7. Verification after deploy

### The health-check script

`docker/health-check.sh` is the definition of "deployed successfully". Its
interface, exactly:

```text
Usage: docker/health-check.sh [BACKEND_URL] [FRONTEND_URL]

Positional arguments (both optional)
  $1 BACKEND_URL   default: ${BACKEND_URL:-http://127.0.0.1:${STAGING_BACKEND_PORT:-18080}}
  $2 FRONTEND_URL  default: ${FRONTEND_URL:-http://127.0.0.1:${STAGING_FRONTEND_PORT:-8081}}

Environment overrides
  BACKEND_URL              backend base URL (no /actuator/health suffix)
  FRONTEND_URL             frontend base URL
  HEALTH_TIMEOUT_SECONDS   total budget per check, default 180
  HEALTH_INTERVAL_SECONDS  delay between retries, default 5
  CURL_MAX_TIME            per-request curl timeout, default 10

Exit status
  0  both checks passed
  1  at least one check failed (the failing check is named on stderr)
  2  bad invocation or unusable environment: `curl` is missing, or a tuning
     variable is not a positive integer. 0 counts as INVALID.
```

Behaviour worth knowing:

- **Backend**: polls `$BACKEND_URL/actuator/health` until HTTP 200 **and** the
  **top-level** `status` member of the body is exactly `"UP"`. Nested objects are
  stripped before the comparison, so a body like
  `{"status":"DOWN","components":{"db":{"status":"UP"}}}` is a **failure**, not a
  pass — an unanchored substring match would report a DOWN backend as healthy and
  let a broken deploy through with no rollback. The parsing is pure bash; `jq` is
  not assumed to exist on the host.
- **Frontend**: polls `$FRONTEND_URL/` until HTTP 200 with a body containing at
  least one non-whitespace character; redirects are followed, and an empty **or
  whitespace-only** 200 is rejected because that is what a broken nginx document
  root looks like.
- **Both checks always run**, even if the first fails, so one run reports
  everything that is broken.
- Trailing slashes on the URLs are stripped. The three tuning variables must be
  **positive integers greater than 0** — `0` is rejected, because a zero budget
  collapses the retry loop to a single attempt (and `sleep 0` spins it) while
  looking like a legitimate setting.
- `curl` must exist on the machine running it. It never prints a credential — only
  URLs and HTTP status codes — so its output is safe to attach to a CI log.
- Worst case runtime is roughly `2 × HEALTH_TIMEOUT_SECONDS` (180 s each by
  default), inside the workflow's 8-minute step timeout. The workflow does not
  override the tuning values, so the script defaults apply.

#### Where the URLs come from — two different cases

The two cases differ, and conflating them is a documented mistake of an earlier
revision of this document:

| Situation | Backend / frontend URL |
| --- | --- |
| **A hand-run `bash ./health-check.sh` on the host** | The `BACKEND_URL`/`FRONTEND_URL` **shell environment** variables, falling back to `${STAGING_BACKEND_PORT:-18080}` / `${STAGING_FRONTEND_PORT:-8081}` — also from the shell environment. **Nothing sources the env file for you.** |
| **The deploy workflow** | The workflow **derives them from the operator's env file** using `resolve_probe_url`: it reads `STAGING_BACKEND_PORT` / `STAGING_FRONTEND_PORT` from the env file (falling back to 18080 / 8081), and passes the resulting URLs as positional arguments (and in the environment). A `STAGING_*_URL` variable overrides only when it does not **name a different port** than the file — see [§2.3](#23-variables). |

So the workflow cannot probe the wrong port after an operator moves it, but a
hand-run script can. Run it explicitly:

```bash
cd /opt/roomflow/staging
bash ./health-check.sh
# or be explicit, which is what the workflow does:
bash ./health-check.sh http://127.0.0.1:18080 http://127.0.0.1:8081
```

Caveat when running it by hand: if you changed `STAGING_BACKEND_PORT` /
`STAGING_FRONTEND_PORT` in `.env.staging`, a bare `bash ./health-check.sh` will
probe the defaults and report a failure — pass the URLs as arguments instead.

### Manual checks

```bash
# on the staging host
cd /opt/roomflow/staging
export COMPOSE="docker compose --project-directory /opt/roomflow/staging \
  --env-file /opt/roomflow/staging/.env.staging \
  -f /opt/roomflow/staging/docker-compose.staging.yml"

$COMPOSE ps                      # all five services, health status, and which image each runs
docker inspect --format '{{.Config.Image}}' roomflow-staging-backend   # the live tag
curl -sS http://127.0.0.1:18080/actuator/health                        # expect {"status":"UP",...}
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/       # expect 200
$COMPOSE logs --tail=100 backend
```

**The middleware ports, and the backend port, are bound to `127.0.0.1` on the
staging host** — they are not reachable from your laptop, and the health check
cannot be run from a GitHub runner. To check them from elsewhere, open an SSH
tunnel (the pattern used in `docs/operations/middleware.md` for the dev stack):

```bash
ssh -p 22 -N -o ExitOnForwardFailure=yes \
  -L 18080:127.0.0.1:18080 \
  -L 13307:127.0.0.1:13307 \
  -L 16380:127.0.0.1:16380 \
  -L 15674:127.0.0.1:15674 \
  -L 15675:127.0.0.1:15675 \
  deploy@staging.example.com
# then, locally: curl http://127.0.0.1:18080/actuator/health
```

The frontend is the one deliberately public port (`${STAGING_FRONTEND_PORT:-8081}`,
published on all interfaces), so `http://staging.example.com:8081` should load the
SPA directly — unless a firewall or cloud security group blocks it. There is no
TLS in this slice; see §10.

---

## 8. Running the staging E2E suite

The `staging` Playwright project matches every `staging-*.spec.ts` file:

- `frontend/tests/e2e/staging-smoke.spec.ts` — the core acceptance flow against a
  **deployed** stack: register two users via the API → log in through the real UI →
  create a meeting → second user signs up (报名) → the organizer receives the signup
  notification via RabbitMQ → marks it read. No dev server, no mocks, no
  `webServer` block.
- `frontend/tests/e2e/staging-acceptance.spec.ts` — the full acceptance list:
  admin room management (create/edit/disable/enable/delete via the real admin UI,
  cross-checked over the API), the normal-user admin-surface rejection, the whole
  meeting lifecycle (create/update/cancel/logical-delete), signup/withdraw/rejoin/
  kick/permanent-ban participant flows, notification delivery + mark-read, the
  permission boundaries, and — in real wall-clock time — end-early plus the
  scheduler's auto-end sweep. See the file's header for why the timing tests wait
  for real 15-minute boundaries (the contract forbids past-start bookings and the
  staging database is unreachable from outside, so the dev suite's MySQL rewrite
  is impossible there).

The canonical executor is the `E2E Staging` GitHub Actions workflow
(`.github/workflows/e2e-staging.yml`): `environment: staging`, an SSH tunnel to
the loopback-bound frontend, then `npm run test:e2e:staging`. It runs on
`workflow_dispatch` and after every successful `Deploy Staging` on `main` —
never on push/PR.

### Two independent target chains

The Playwright config and `helpers.ts` resolve **two separate chains**: a dev/CI
chain and a staging chain. They are mirrored in three places
(`playwright.config.ts`, `tests/e2e/helpers.ts`, and the guard at the top of the
smoke spec) and are meant to be kept in step.

```text
dev / CI   — the `chromium` project (every committed spec except the smoke one)
  UI  base : E2E_BASE_URL     -> http://localhost:5173   (global `use.baseURL`)
  API base : E2E_API_BASE_URL -> http://localhost:8080   (tests/e2e/helpers.ts)

staging    — the `staging` project (every staging-*.spec.ts file)
  UI  base : PLAYWRIGHT_BASE_URL -> STAGING_BASE_URL     (that project's OWN use.baseURL)
  API base : STAGING_API_BASE_URL -> the staging UI URL  (same origin)
```

Key consequences — several of these are the opposite of the earlier design:

- **A staging URL no longer repoints the dev suite.** The staging URL is the
  `staging` project's OWN `use.baseURL`; the global `use.baseURL` stays on the dev
  chain for good. A top-level staging URL would be inherited by every project, so an
  exported `STAGING_BASE_URL` would have silently pointed `auth`/`meeting-flow`/
  `permission` at a shared, publicly reachable deployed database.
- **`E2E_BASE_URL` is now the dev-only chain.** `PLAYWRIGHT_BASE_URL` is a
  staging-class variable: it selects the `staging` project's target and does **not**
  move the dev suite. To aim the dev suite at another UI, use `E2E_BASE_URL`.
- **`E2E_API_BASE_URL` is ignored entirely whenever a staging URL is set**, and
  `helpers.ts` prints a warning saying so. Conversely `STAGING_API_BASE_URL` is
  ignored when no staging URL is set (also warned). There is no longer an
  "asymmetric precedence" between the two chains.
- **The API base is derived from the staging URL**, because the deployed stack
  serves the SPA and `/api/` from **one origin** (`docker/nginx.conf` proxies
  `/api/` to the backend). `STAGING_API_BASE_URL` overrides that for the single
  case where the API is reachable somewhere else from the runner.
- **The `staging` project exists only when a staging URL is configured.** With no
  env vars set, only `chromium` exists and it `testIgnore`s the smoke spec — so
  plain `npx playwright test` and CI never try to reach a host that does not exist.
- **The smoke spec is skipped unless BOTH a staging URL and the password are set**
  (`test.skip(...)` at the top of the file).
- **Do not mix the environments in one shell.** `helpers.ts` resolves ONE API base
  from the environment, not per project, so exporting a staging URL and then
  running the dev suite (`npx playwright test`) would leave `chromium` on the dev
  UI while every helper call went to the staging API. Run the staging project on
  its own and keep the staging variables out of a dev run.

### Required credentials: `STAGING_E2E_PASSWORD` and `STAGING_ADMIN_PASSWORD`

The staging specs create **real accounts with a real password** on a shared,
publicly reachable database, and the project has **no delete-user API**, so those
accounts stay login-capable afterwards. The password the rest of the dev suite uses
(`helpers.TEST_PASSWORD`) is committed to this public repository, so reusing it on
staging would leave accounts whose credentials are world-known — and organizers can
read participant data. The acceptance spec additionally logs in as the seeded
`admin` account (room management UI, admin-overrides-organizer checks), whose
password is known only to the operator who generated `ADMIN_PASSWORD_HASH`.

Both passwords are therefore supplied **from the environment only**:

- In CI they are **environment-scoped secrets on `staging`**
  (`STAGING_E2E_PASSWORD`, `STAGING_ADMIN_PASSWORD`), readable only by jobs that
  pass the environment gate — see the `E2E Staging` workflow header.
- `npm run test:e2e:staging` **refuses to start without them**: the
  `pretest:e2e:staging` hook in `frontend/package.json` checks for a staging URL,
  then for `STAGING_E2E_PASSWORD`, then for `STAGING_ADMIN_PASSWORD`, and fails
  with an actionable message instead of letting Playwright report
  `Project(s) "staging" not found`.
- There is **no fallback** to the old literal dev password: `STAGING_PASSWORD` and
  `STAGING_ADMIN_PASSWORD` in `helpers.ts` resolve to `''` when unset —
  fail-closed, and admin-dependent tests skip.
- The staging Playwright project runs with **`trace: 'off'` and `video: 'off'`**
  (screenshots on failure only): a retained trace captures DOM state including
  input values, and run artifacts are downloadable — a typed credential must never
  reach one.

### Command

```bash
cd frontend
npx playwright install chromium     # one-off; browsers are not installed by npm ci

STAGING_BASE_URL=https://staging.example.com \
STAGING_E2E_PASSWORD='<a password that lives outside this repository>' \
STAGING_ADMIN_PASSWORD='<the seeded admin password>' \
npm run test:e2e:staging
```

`test:e2e:staging` is `playwright test --project=staging`.

- `STAGING_BASE_URL` (or `PLAYWRIGHT_BASE_URL`) must be reachable **by a browser**.
  Because the middleware and backend ports are loopback-bound, do not point it at
  `127.0.0.1:<port>` unless you are running the test on the staging host itself —
  from a laptop, use the public frontend URL (nginx proxies `/api/` to
  `backend:8080`) or an SSH tunnel plus a local port.
- `STAGING_API_BASE_URL` is **not needed** in the normal case: the API base is
  derived from the staging URL (same origin). Set it only if the API is reachable
  somewhere other than the staging origin.
- The spec asserts, before its first write, that the helper API base is **not**
  the local dev API — so a silent UI/API split fails immediately instead of
  producing a false green.

### Residual exposure (documented, not solved)

**There is no delete-user API in this project, so every run permanently adds its
registered accounts to the shared staging database** (the smoke spec: two; the
acceptance spec: up to six across its tests). Meetings and rooms, by contrast,
are cleaned up: the acceptance spec cancels or deletes every meeting it creates
and disables/deletes every scratch room via the API. The env-supplied password is
the most the specs can do for the leftover accounts — deleting them requires a
backend API that does not exist yet. Until then the mitigation is operational —
treat the staging database as dirty, keep it off the public internet, and rotate
`STAGING_E2E_PASSWORD` if it leaks. The accounts are identifiable by their
`stg_*` username prefixes.

Two further caveats, both real: staging data is a **shared** database (other runs,
manual testing and seeded demo data live in it), so the specs name every entity
uniquely and assume nothing about emptiness; and because the test accounts are
created fresh by the run, their inboxes contain only the notifications that run
produced, which is why the notification assertions are unambiguous even on a dirty
database.

---

## 9. Troubleshooting

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Deploy fails: `no usable env file found for the staging stack` (names `.env.staging` first, then `.env`) | No env file in the deploy dir, or `STAGING_ENV_FILE` names a file that does not exist (when set, there is no fallback by design) | Create it: `cp .env.staging.example <deploydir>/.env.staging` (get the template there from a clone — the workflow does not ship it), or clear `STAGING_ENV_FILE` |
| Deploy fails: `STAGING_ENV_FILE must be a bare file name inside the deploy dir` | The variable was set to a **path** (`/etc/roomflow/.env.staging`, `config/.env`, `..`) — only a bare name inside the deploy dir is accepted now | Set the variable to a bare name such as `.env.staging`, or unset it and let auto-detection find `.env.staging` / `.env` |
| Deploy fails: `missing or empty in <file>: DB_PASSWORD JWT_SECRET …` | The file exists but values are empty (`VAR=`, `VAR=""` and `VAR=''` all count as empty). Names are derived from the compose `${VAR:?}` guards; the message lists **names** only | Fill in every listed value; re-run. Secrets are never printed |
| Deploy fails: `... contains a '$' that Compose will re-interpolate` | A value other than the BCrypt hash holds a literal `$`, which Compose would expand — the container would receive an empty secret | Escape every `$` as `$$`, or single-quote the whole value (Compose uses single-quoted values verbatim) |
| Deploy **succeeds with a warning**: `hold an unescaped BCrypt hash` | `ADMIN_PASSWORD_HASH` is written bare or double-quoted | Make it unambiguous by single-quoting the whole value: `ADMIN_PASSWORD_HASH='$2a$10$…'`. The deploy proceeds on purpose; if the health check then fails, this is the first thing to check |
| Backend restart-loops; `/actuator/health` never answers; health check times out | `ADMIN_PASSWORD_HASH` missing, or its `$` characters mangled by Compose interpolation | Set it to an offline BCrypt hash, **single-quoted** (`'$2a$10$…'`). Then `docker compose --project-directory /opt/roomflow/staging --env-file /opt/roomflow/staging/.env.staging -f /opt/roomflow/staging/docker-compose.staging.yml logs backend` to confirm the Flyway placeholder error is gone |
| Deploy fails or the health check reports `config_error` with `STAGING_BACKEND_URL=… and STAGING_BACKEND_PORT=… disagree about the port` | A `STAGING_*_URL` repository variable names a different port than the env file publishes | Unset the variable (the URL is then derived from the env file's port), or align `STAGING_*_PORT` in the env file. Setting the variable to the loopback default is the usual cause |
| Deploy fails: `cannot load the deploy library from '<dir>/staging-deploy-lib.sh'` | The deploy dir is wrong, or `staging-deploy-lib.sh` was never shipped to it (the ship step did not run / the host was prepared by hand) | Check `STAGING_DEPLOY_DIR`; re-run the workflow so all three artifacts are shipped, or copy `.github/scripts/staging-deploy-lib.sh` into the deploy dir |
| Health check reports `config_error` (exit 2), no rollback, red job | The **host** is misconfigured, not the deploy: no `curl`, a non-positive tuning value, an unusable env file, or a probe URL that disagrees with the env file's port. The workflow deliberately does not roll back for exit 2 | Fix the host (install `curl`, correct the env file/ports) and re-run. Staging is left as deployed |
| Log line `GHCR_READ_TOKEN is not set; skipping 'docker login'` | Not an error: the packages are public, so the login is skipped and nothing is written to the host's docker config | None. Set `GHCR_READ_TOKEN` only if the packages are made private |
| Backend is up but the seeded `admin` account cannot log in | `ADMIN_PASSWORD_HASH` still holds the template's `CHANGE_ME_bcrypt_hash` (non-empty, so every guard passes) — or the DB volume predates the change, so Flyway never re-ran `V2` | Set a real hash on a **fresh** staging database volume; on an existing one, update the `admin` row directly (Flyway seeds once) |
| Health check times out; a manual `docker compose --project-directory /opt/roomflow/staging --env-file /opt/roomflow/staging/.env.staging -f /opt/roomflow/staging/docker-compose.staging.yml ps` shows services healthy | Backend still booting, or a middleware dependency flapping | Read `docker compose --project-directory /opt/roomflow/staging --env-file /opt/roomflow/staging/.env.staging -f /opt/roomflow/staging/docker-compose.staging.yml logs --tail=200`; the script's budget is 180 s per check. Both checks always run, so one run shows both failures |
| Health check passes but the backend reports `DOWN` | Not possible from the script: it strips nested objects and requires the **top-level** `status` to be `UP`. If you are reading this from your own `grep`, you are matching a nested component | Use `docker/health-check.sh` (or `curl -sS .../actuator/health`) and read the top-level `status` |
| `docker compose` starts with empty secrets / MySQL refuses to start (manual runs) | Missing `--project-directory` and/or `--env-file`. Compose then resolves `${VAR}` from the directory of the first `-f` file and interpolates empty strings | Always pass both, exactly as the workflow does. Compose would otherwise invoke only warnings, not an error |
| Pull fails: `manifest unknown` / `not found` for the SHA | The SHA was never pushed to GHCR — CI builds images for PRs and feature branches but only pushes them on a `push` to `main` — or the SHA was mistyped/truncated | Use a full 40-char SHA from `git log origin/main` or the GHCR tag list (§6). Short SHAs are rejected earlier by the workflow's own gate |
| Pull denied / `unauthorized` on the host | The `roomflow-*` packages were made **private** and `GHCR_READ_TOKEN` is unset (or the PAT expired / lacks `read:packages`) | Either make the packages public again, or set `GHCR_READ_TOKEN`/`GHCR_USER` so the conditional login runs. To fix by hand: `docker login ghcr.io --username <user> --password-stdin`, feeding the token over stdin so it stays out of `ps` |
| Pull fails on the host: `dial tcp … i/o timeout` to ghcr.io | Host has no outbound network access / egress proxy | Open outbound HTTPS to `ghcr.io` from the staging host |
| `ssh: connect to host … port …: Connection refused` / timeout | Host down; `STAGING_SSH_PORT` wrong; firewall or cloud NSG blocks the runner; `sshd` not running | Check the host, then `STAGING_SSH_PORT`, then the firewall. Reproduce with `ssh -v -p <port> <user>@<host>` |
| `Permission denied (publickey)` | Public key not in the deploy user's `authorized_keys`; wrong secret value; `IdentitiesOnly` picking the wrong key | Re-install the public key (§3), re-set `STAGING_SSH_PRIVATE_KEY`, test the exact key by hand with `-i` and `-o IdentitiesOnly=yes` |
| `Host key verification failed` | Host rebuilt (new key) while `STAGING_KNOWN_HOSTS` pins the old one — or you are pointed at a different host | Refresh the secret with a fresh `ssh-keyscan` **after verifying the new fingerprint out of band**. Do not "fix" this by deleting the secret permanently — that downgrades to trust-on-first-use |
| Deploy fails at the ship step: `mkdir -p` permission denied | Deploy user cannot write the deploy dir's parent (default `/opt/roomflow/staging` is root-owned) | Create and chown the directory to the deploy user once (§4.2) |
| `docker: command not found`, or permission denied on the Docker socket | Docker absent, or the deploy user is not in the `docker` group (the workflow never uses `sudo`) | Install Docker + compose plugin; `sudo usermod -aG docker <user>` and reconnect |
| Health check exits 2: `curl is required but was not found` | `curl` not installed on the host | Install it; the check must stay on the host because the ports are loopback-bound |
| Health check passes but the browser cannot log in / API calls fail | `CORS_ALLOWED_ORIGINS` does not match the origin the browser sees — e.g. the value still carries the template's `CHANGE_ME_` prefix, or the wrong scheme/host/port | Set it to the exact scheme+host+port (comma-separated for several). An empty value means "no cross-origin requests allowed" |
| Frontend unreachable from your browser | `STAGING_FRONTEND_PORT` not open in the firewall/security group, or the container is not running | `docker compose --project-directory /opt/roomflow/staging --env-file /opt/roomflow/staging/.env.staging -f /opt/roomflow/staging/docker-compose.staging.yml ps` on the host; open the port; check `STAGING_FRONTEND_PORT` in the env file matches the URL you use |
| Backend/middleware unreachable from your laptop | Expected: those ports are bound to `127.0.0.1` on the host | Use an SSH tunnel (§7), or run the check on the host |
| Dev and staging stacks collide on one host | Both stacks published the same host port | They are designed not to: dev uses `13306 / 16379 / 15673 / 15672 / 8080 / 80`; staging uses `13307 / 16380 / 15674 / 15675 / 18080 / 8081`. If you override a staging port, keep it out of the dev set. Container names, volumes and networks are prefixed (`roomflow-staging-*`, `roomflow-staging_staging-*`) so they cannot collide |
| `gh workflow run` says the workflow was not found | The workflow file is not on the ref you dispatched | Dispatch against a ref that has it. Note the UI only offers the workflow once it is on the **default branch** — currently `ci/github-actions` (§1) |
| Dispatch rejected: `Invalid image_tag` / `must be the full 40-character commit SHA` | The input is not hex, or is a short SHA | Pass the full 40-char SHA (`git log --format=%H`) |
| Every deploy "skipped" after a merge | CI did not succeed for that `main` commit, the branch filter did not match, **or the workflow is not on the default branch** | Check `gh run list --workflow=CI --branch main`; the deploy job requires `conclusion == 'success' && head_branch == 'main'`. If CI is green and nothing ran at all, read [§1](#the-default-branch-caveat) |
| The deploy job is waiting for approval and nothing happens | The `staging` environment has required reviewers configured, and this is the gate working | Approve it in the run's UI (`gh run view <run-id>` links to it) |
| Rollback step reports `impossible - no previous tag was captured` | First deploy on a fresh host (no container to inspect), or the running container was created from a non-SHA tag (`latest`, a digest) | Roll back manually with `workflow_dispatch -f image_tag=<sha>` and make sure everything is deployed by SHA afterwards |
| Rollback reports `**not performed**` | The rollback's own pre-flight rejected the configuration (bad deploy dir, unusable env file, invalid tag/URL) — nothing on the host was changed | Fix the reported configuration problem; the deployed version is still the failed one |

Also worth knowing: **if** you set `GHCR_READ_TOKEN`, the credential from the last
successful deploy stays in the host's docker config (base64, not encrypted). Rotate
the PAT — the workflow cannot clean it up. With the token unset (the default) the
login never runs and nothing is stored.

---

## 10. Known limitations

Read this section before trusting anything above.

**A staging host exists and is deployed** — see §12 for the first-deployment
record and §13 for the acceptance-completion record. Host `20.205.103.75`, SSH
port 2222, deploy user `deploy`, deploy dir `/opt/roomflow/staging`, env file
`.env.staging`; the stack runs healthy on the merged `main` image tag.

**The default branch is now `main`** (previously `ci/github-actions`), so the
`workflow_run` chain CI → Deploy Staging fires as designed on every merge.

**The `staging` environment is configured**: required reviewers
(`niuyeyeplus`, `prevent_self_review: false`), a deployment branch policy, and
the credential secrets at environment scope. Every deploy and every E2E run
pauses at that gate until approved.

**TLS/HTTPS remains out of scope** — the frontend is published over plain HTTP on
its configurable port, and nothing here terminates TLS. The port is not publicly
reachable today (NSG), so the documented access path is the SSH tunnel (§7, §8).

**What remains unverified or is a residual exposure** (supersedes the former
"could not be verified without a real host" list — the health check, compose
convergence, SSH path, rollback path, Flyway-on-staging and the E2E smoke spec
were all exercised for real during the first deployment, §12):

- The rollback branch "the previous version is not healthy either" has never
  fired; the happy-path rollback was exercised once (§12).
- A fresh-host cold start (first `compose up` on an empty volume) has only
  happened once; repeat-provisioning behaviour is untested.
- The E2E smoke spec's later assertions were proven on staging; the acceptance
  spec added by this slice is verified in §13.

**Validation that was actually performed, and what was not.** The environment in
which these artifacts were revised has **no `docker` CLI, no `shellcheck` and no
`actionlint`** on `PATH` (verified — `command -v` finds none of the three).
Consequently:

- the compose file has **not** been validated with `docker compose config`, so this
  document does not claim it is Compose-valid — it was reviewed statically, and the
  required-variable extraction from its `${VAR:?}` guards was executed;
- `docker/health-check.sh` and `.github/scripts/staging-deploy-lib.sh` were checked
  with `bash -n` (syntax only) and the library's functions were **executed locally
  against stub compose/env files** — which is how its documented behaviours below
  were confirmed, and is the only execution these artifacts have had;
- `deploy-staging.yml` was checked by parsing it as YAML, which catches syntax
  errors but not expression or context mistakes. It has **not** been linted with
  `actionlint` in this environment (not on `PATH`).

**Behaviours of the shared library that were executed against stubs** (stated here
so they are not mistaken for live-deploy evidence):

- `STAGING_ENV_FILE` rejection of `/etc/passwd`, `..`, `.`, `a/b` and a value
  containing a newline; acceptance of `.env.staging`, `.env` and empty.
- `STAGING_DEPLOY_DIR` rejection of a relative path and of a value containing a
  newline; acceptance of an absolute path. (`..` inside an absolute path is
  permitted by the character class — the value is still absolute.)
- `resolve_env_file` choosing `.env.staging` first and `.env` only when
  `.env.staging` is absent.
- `resolve_probe_url` deriving `http://127.0.0.1:9081` from
  `STAGING_FRONTEND_PORT=9081`, defaulting the backend to `18080`, **rejecting** an
  override whose port disagrees with the file, and **accepting** a port-less
  `https://staging.example.com`.
- `require_env_vars`: a bare BCrypt hash produces a **warning** and exit 0; the same
  hash single-quoted is silent; any other literal `$` is a **hard failure** (exit 1)
  naming only the variable names.

**The values written into `~/.ssh/config` are now validated — statically.** An
earlier revision of this document described `STAGING_SSH_PORT` as an unvalidated
gap. That is no longer the case: the port, `STAGING_HOST` and `STAGING_USER` are
each refused before the config file is written if they could start a new directive,
and `GHCR_USER` is refused if it holds a single quote or a line break
([§3](#3-ssh-deploy-key-setup)). These guards have since been exercised on real
runner-to-host deploys (§12): they are fail-closed by construction — a rejected
value is never written, and a value that got past them would break ssh's parse of
the whole file rather than quietly taking effect.

**Other accepted trade-offs carried from the artifacts (not bugs, but real):**

- The Redis password is passed via `sh -c "redis-server --requirepass
  \"$$REDIS_PASSWORD\""`, so `docker inspect .Config.Cmd` shows the literal
  `$REDIS_PASSWORD` rather than the plaintext — but the value is still present in
  the container's environment block (`docker inspect .Config.Env`) and in `ps`
  output. This is **not** a full secret-store solution; Docker secrets or a mounted
  `redis.conf` is the follow-up for anything more sensitive than a staging box.
- Everything else in `.env.staging` is likewise plain environment: anyone who can
  run `docker inspect` on the host (i.e. anyone in the `docker` group) can read the
  stack's credentials.
- **If** `GHCR_READ_TOKEN` is set, the credential it stores is left in the host's
  docker config after the run (base64, not encrypted) — see §2.2 and §9.
- A rollback re-deploys an older image **against the current database**. Flyway
  migrations are forward-only, so a rollback across a schema change can come up
  unhealthy; there is no database rollback in this slice.
- The staging E2E specs leave their registered accounts behind on the shared
  staging database on every run, because no delete-user API exists; meetings and
  rooms created by the acceptance spec are cleaned up
  ([§8](#8-running-the-staging-e2e-suite)).
- The deploy workflow ships `docker-compose.staging.yml`, `health-check.sh` and
  `staging-deploy-lib.sh` from the deployed revision but **never ships
  `.env.staging.example`**, even though its own error message suggests
  `cp .env.staging.example <deploydir>/.env.staging`. That copy only works if the
  template is already on the host (see [§4.4](#44-the-env-file)).
- An unescaped BCrypt `ADMIN_PASSWORD_HASH` is a **warning, not a pre-flight
  failure**, on purpose — so the mistake is only caught by the health check, which
  costs a deploy and an automatic rollback to discover
  ([§4.5](#45-startup-critical-variables)).
- `PLAN.md` describes `deploy-staging` as a stage of the CI pipeline; the
  implementation is a separate workflow chained by `workflow_run` (§1).

---

## 11. Production (forward pointer)

Production deployment is a **separate later slice, `PR-6`
(`feat/production-deploy`)**, which per `PLAN.md` adds manual confirmation before
deploying, database backup and restore verification, and a handover document
(`docs/handover.md`). It is not covered here, and this document should not be read
as describing production behaviour: the staging workflow deploys automatically on
merge, hosts a single stack, has no backup/restore step and no TLS. Anything
production-specific — the confirmation gate, backups, the domain, HTTPS — belongs
to that slice.

---

## 12. First deployment — acceptance record (2026-09-16)

The first real staging deployment ran on 2026-09-16 against host
`20.205.103.75` (SSH port 2222, deploy user `deploy`, deploy dir
`/opt/roomflow/staging`, env file `.env.staging`). Record of what happened, what
was fixed to get there, and what is still open.

### Timeline and runs

| Step | Result | Run / PR |
| --- | --- | --- |
| Staging machinery merged to `main` | merged | PR #6 (`dc4054e`) |
| CI on `main` (images `dc4054e5db940688ffd33fcf66aa650c744e2db6` + `latest`) | success | [run 35108706172](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35108706172) |
| Deploy `dc4054e5...` (workflow_run) | **success on attempt 4** — see failures below | [run 35109192206](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35109192206) |
| E2E staging smoke against `dc4054e5...` | 1 pass after fixes | local run via SSH tunnel |
| Test/healthcheck fixes merged | merged | PR #7 (`a59a3b7`) |
| CI on `main` (images `a59a3b72f74f660a119bfe2b87fba7d200ddf9f4`) | success | [run 35113894247](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35113894247) |
| Deploy `a59a3b72...` (workflow_run) | success; rollback target `dc4054e5...` captured | [run 35114343719](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35114343719) |
| E2E staging smoke against `a59a3b72...` | 1 pass, 60s | local run via SSH tunnel |
| Rollback verification: dispatch with `image_tag=38ff0a87df89d268d05c11159cb80b122f8eafc5` (valid SHA, no published image) | deploy failed as designed (remote exit 2); **automatic rollback restored `a59a3b72...` and verified healthy** | [run 35114626866](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35114626866) |

Environment approvals were performed via the `pending_deployments` API by the
required reviewer (`niuyeyeplus`; `prevent_self_review: false`). Every run above
paused at the `staging` environment gate until approved — the gate works.

### Failures hit on the first deploy and their fixes

1. `STAGING_SSH_PORT` unset → defaulted to 22. Fixed: `gh variable set
   STAGING_SSH_PORT --env staging --body "2222"` (now set at environment scope).
2. `Host key verification failed` (`No ED25519 host key is known for
   [host]:2222`): the stored `STAGING_KNOWN_HOSTS` pinned a key that did not
   cover `[host]:2222` for ed25519 (ssh prefers ed25519; a pin listing only
   another key type — or one recorded for a different port — fails strict
   checking). Fixed by re-setting the secret to the full `ssh-keyscan -p 2222 -t
   ed25519,ecdsa,rsa` output for the host. **Caveat:** the replacement was
   produced by `ssh-keyscan` and cross-checked against a `known_hosts` entry
   this machine recorded earlier for the same host; it was not verified against
   a server-side fingerprint. Recommended: verify once out of band (e.g. cloud
   console → `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`).
   Runbook note: generate the pin with the real port (`ssh-keyscan -p <port>`)
   and include `ed25519` — `ssh-keyscan` defaults may not return every type.
3. `Load key ... error in libcrypto` then `Permission denied (publickey)`:
   `STAGING_SSH_PRIVATE_KEY` was corrupted at upload (the local OpenSSH ed25519
   key loads fine). Fixed by re-uploading the key bytes verbatim.
   The deploy user's `authorized_keys` and host prerequisites
   (`deploy` user, `docker` group, `/opt/roomflow/staging`, `.env.staging`)
   were then provisioned on the host per §4 — they did not exist yet.

### Health check result

On the successful deploys the on-host check (`docker/health-check.sh`) reported
`backend http://127.0.0.1:18080/actuator/health -> HTTP 200, top-level status
UP` and `frontend http://127.0.0.1:8081/ -> HTTP 200, non-blank body` on the
first attempt. On the host: all five `roomflow-staging-*` containers
`healthy`, Flyway applied migrations 1–3 cleanly (including `seed rooms`), and
the seeded `admin` login works (BCrypt hash written single-quoted — the
interpolation pitfall in §4.5 did not bite). The dev stack on the same host was
untouched throughout.

### Rollback verification

`gh workflow run deploy-staging.yml -f image_tag=38ff0a87...` (a commit that CI
never built): `docker compose up -d` failed on the host with remote exit 2, the
health-check step was skipped, the rollback step ran, redeployed the previous
tag `a59a3b72...` and re-verified it healthy (`ROLLBACK_HEALTH=healthy`).
Post-check on the host confirmed backend + frontend healthy on `a59a3b72...`.
This is the designed behaviour; the run correctly ends red while the host is
left healthy on the previous tag.

### E2E acceptance

`npm run test:e2e:staging` with `PLAYWRIGHT_BASE_URL=http://localhost:8081`
(browser traffic via `ssh -L 8081:127.0.0.1:8081` tunnel to the staging host —
the documented access path) and an env-only `STAGING_E2E_PASSWORD`:

`staging-smoke` — **PASS** (2 runs: `dc4054e5...` and `a59a3b72...`).
Covers: register (API) → UI login → create meeting → second user 报名 →
organizer PARTICIPANT_JOINED notification → mark-read in UI.

**Coverage gaps vs the broader acceptance list** — NOT covered by the staging
spec today: room management UI (the spec only lists rooms via API to pick one),
meeting lifecycle transitions (start/end/cancel — only covered by
`meeting-lifecycle.spec.ts` in the dev suite, not on staging), and any
admin-only flows. Do not read the smoke pass as covering those.

### Issues found by the acceptance run (fixed in PR #7)

- `frontend/tests/e2e/helpers.ts` `fillTimeRange` still targeted the removed
  `.el-range-editor` — MeetingForm.vue uses two `el-date-picker
  type="datetime"` inputs since `fb0a974`. The staging spec timed out on the
  create-meeting dialog; fixed by filling `开始时间`/`结束时间` inputs.
  **Same staleness affects the dev specs** (`meeting-flow.spec.ts`,
  `meeting-lifecycle.spec.ts` share the helper) — re-verify them on the dev
  stack; the invalid-time cases in `meeting-flow` may need further adjustment.
- `docker-compose.staging.yml` frontend healthcheck probed
  `http://localhost:80/`; in `nginx:alpine` `localhost` resolves to `::1` while
  nginx listens on IPv4 only → container reported `unhealthy` while serving
  HTTP 200. Now probes `127.0.0.1:80`.
- Browser calls to the API carried `Origin: http://localhost:8081` (the tunnel
  URL), which `CORS_ALLOWED_ORIGINS` rejected with 403 → login failed in the
  browser while Node-side API calls worked. Added `http://localhost:8081` to
  `CORS_ALLOWED_ORIGINS` on the host (kept alongside the public origin).

### Still open

- **Frontend public reachability**: `0.0.0.0:8081` is bound by docker-proxy but
  port 8081 was not reachable from this machine — likely NSG/firewall. Until it
  is opened (or a domain/TLS is fronted), the UI is reachable only through an
  SSH tunnel, and staging E2E must run via a tunnel or on the host.
- **`.env.staging` provenance**: generated on the host during first-deploy
  provisioning (random hex secrets, single-quoted BCrypt admin hash). It is
  operator-owned; rotate any value that should not have passed through the
  orchestrator's session.
- **`STAGING_E2E_PASSWORD`** was supplied ad hoc for the acceptance runs, not
  stored anywhere; set it (env or CI secret) wherever future staging E2E runs.
- Backend healthcheck still uses `localhost:8080` inside the container — it
  resolves correctly in the JRE image today, but `127.0.0.1` would be more
  robust for the same reason as the frontend fix.
- Dev-suite specs sharing `fillTimeRange` were not re-run against the dev
  stack here.

---

## 13. Staging acceptance — completion record (2026-09-17)

Branch `feat/staging-acceptance`, PR #9, base `main` @ `24f7371`. This slice
had two workstreams: fix the flaky `MeetingLifecycleIT` at its root cause, and
expand staging Playwright coverage to the full acceptance list with a real
`E2E Staging` workflow. What ran, what is blocked, and what is residual.

### The `MeetingLifecycleIT` flake — root cause and fix

- **Root cause** (`sweepEndsExpiredMeetingAndNotifiesActiveParticipants`):
  the test inserted an *already-overdue* meeting and added participants
  afterwards. With the 500 ms test sweep the scheduler could end the meeting
  in the gap between insert and participant rows; end-notifications are
  generated once, at end time, for the participants present then — so the
  awaited notification could never arrive. That is the 30 s `awaitTrue`
  timeout flake, and no timeout increase could fix it.
- **Fix** (`34f7f96`): insert the meeting with a *future* `end_time`
  (invisible to `findOverdueActiveMeetingIds`), add every participant, then
  flip `end_time` to the past in one `UPDATE`. The sweep can only ever
  observe a fully populated meeting. The second-recipient notification
  assertions were also changed from single-shot asserts to symmetric awaits,
  because RabbitMQ delivery is asynchronous.
- No timeout was raised and no assertion weakened; the ShedLock
  Redis-key observation is unchanged and now documented in a comment.
- `d5149db` is a spotless-formatting commit on the same file; `ae6c7c8`
  widens the default chromium project's `testIgnore` to every
  `staging-*.spec.ts` so a dev run never collects the staging specs.

### Consecutive green CI runs containing the IT

| Run | Event | Result | `MeetingLifecycleIT` |
| --- | --- | --- | --- |
| [35182120358](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35182120358) | push | success | 8/8 pass |
| [35182123332](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35182123332) | pull_request | success | 8/8 pass |
| [35182849895](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35182849895) | push | success | 8/8 pass |
| [35182852633](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35182852633) | pull_request | success | 8/8 pass |

Four consecutive runs, each executing the full Testcontainers suite with the
fixed test. The two earlier runs on this branch (`35181936082`,
`35181965458`) failed at `spotless:check` *before* the test phase — the IT
never ran in them, so they are not counted and do not contradict the streak.

### Staging E2E acceptance suite — written, not yet executed

`.github/workflows/e2e-staging.yml` plus
`frontend/tests/e2e/staging-acceptance.spec.ts` cover all twelve acceptance
items (matrix below). **The suite has NOT been executed against staging**:
the `staging` environment is missing `STAGING_ADMIN_PASSWORD` and
`STAGING_E2E_PASSWORD`. Verified on 2026-09-17 via
`gh api repos/niuyeyeplus/RoomFlow/environments/staging/secrets` — only the
four SSH secrets exist (`STAGING_HOST`, `STAGING_USER`,
`STAGING_SSH_PRIVATE_KEY`, `STAGING_KNOWN_HOSTS`); repository-level secrets
are empty. Per the no-bypass rule, no run was attempted with invented, dev,
or otherwise substituted credentials, and the workflow's own fail-fast step
plus the `pretest:e2e:staging` hook would refuse to start without them anyway.

To execute once the secrets exist:

```bash
gh secret set STAGING_ADMIN_PASSWORD --env staging          # seeded admin's password, via stdin
gh secret set STAGING_E2E_PASSWORD   --env staging          # password for spec-registered accounts
gh workflow run e2e-staging.yml --ref main                  # or --ref feat/staging-acceptance pre-merge
```

The workflow runs on `workflow_dispatch` and, after merge, on every
successful `Deploy Staging` on `main` (`workflow_run`; the trigger file must
live on the default branch, so the chain activates only post-merge).

### Coverage matrix — spec → acceptance items

| # | Acceptance item | Where covered |
| --- | --- | --- |
| 1 | Admin login | `admin room management` — `uiLogin` as `admin`, `.nav-menu` shows the admin entry |
| 2 | Room create/edit/disable/enable/delete | `admin room management` — every step via UI, each verified via API (incl. 40906 booking rejection while disabled) |
| 3 | Create meeting | `meeting lifecycle` — UI create, 201 response + detail page asserted |
| 4 | Update a not-yet-started meeting | `meeting lifecycle` — UI edit, PUT 200, API re-read confirms title |
| 5 | Cancel meeting | `meeting lifecycle` — UI cancel with confirm dialog, 已取消 tag |
| 6 | Logical-delete meeting | `meeting lifecycle` — UI delete, GET returns 404/40401 afterwards |
| 7 | End meeting early | `real-time lifecycle` — waits for the real start boundary, UI 提前结束, `ENDED` + `endedEarly=true` |
| 8 | Auto-end on expiry | `real-time lifecycle` — 15-min meeting on the real 60 s staging sweep, polled until `ENDED`, `endedEarly=false` |
| 9 | Slot release after cancel/delete/end | inline in both tests — the freed interval is rebooked via API and must return 201 |
| 10 | Signup/withdraw/kick/permanent ban | `participant flows` — UI join, UI leave, API rejoin allowed, UI kick, rejoin then rejected 40904 |
| 11 | Notification creation + mark-read | `participant flows` (JOINED/LEFT/KICKED via MQ, UI mark-read) + `real-time lifecycle` (MEETING_ENDED for organizer and participant) |
| 12 | Permission boundaries | `normal user is rejected` (no admin nav, route-guard bounce, 403 on all four admin room endpoints) + `participant flows` (outsider 403 on edit/cancel/end/kick/delete, no manage buttons in UI, admin deleting another user's meeting) |

### Residual staging data and known limits

- Registered `stg_*` accounts remain on the shared staging database on every
  run — there is no delete-user API. They are identifiable by their
  `uniqName` prefixes (`stg_perm`, `stg_lc`, `stg_porg`, `stg_rt`, ...).
- Meetings and rooms are deleted/cancelled/disabled via API on the happy
  path. A mid-test failure can leave a meeting `ACTIVE` until the real sweep
  ends it, and — in `real-time lifecycle` only — a created scratch room
  enabled; both carry `STG-` names for identification.
- Failure artifacts are `frontend/test-results` only (junit/html + failure
  screenshots). Trace and video are OFF for the staging project so no typed
  credential can persist in an uploaded artifact; screenshots of password
  fields render masked.
- The secrets fail-fast step deliberately omits `STAGING_KNOWN_HOSTS`:
  without it the job falls back to `StrictHostKeyChecking=accept-new` with a
  warning, mirroring `deploy-staging.yml`. The secret *is* set today, so
  strict pinning applies.

### Execution record (2026-09-17, local run via SSH tunnel)

Executed locally against the live staging stack on tag `24f7371` via the
documented tunnel path (`ssh -L 8081:127.0.0.1:8081`, §7): the workflow file
is not yet on the default branch, so `e2e-staging.yml` cannot be dispatched
until this PR merges — the Actions run is pending, this record covers the
identical suite run by hand.

| Item | Result |
| --- | --- |
| `STAGING_ADMIN_PASSWORD` / `STAGING_E2E_PASSWORD` | created as `staging` environment secrets on 2026-09-17 (stdin upload, never echoed) |
| Admin credential rotation | old plaintext unknown → new random password, BCrypt hash written to the `account` row and to `.env.staging` (`ADMIN_PASSWORD_HASH`, single-quoted) on the host; backend restarted, `healthy` |
| Admin login verification | `POST /api/auth/login` as `admin` → `code:0` (verified through the tunnel) |
| E2E probe account | `e2e_probe_01` registered + login verified (idempotent probe for future runs) |
| Suite run 1 (spec as written) | 5/6 — `real-time lifecycle` failed: `JWT_ACCESS_TTL` is 15m and the real-time waits (~27min) outlived the test-start tokens; every post-wait API call 401'd as `data:null`. Spec bug, not a product bug — fixed in `d458c35` (re-login after each real-time wait) |
| Targeted re-run of `real-time lifecycle` | **PASS** 39.3m — end-early via UI, auto-end observed by the real sweep, `MEETING_ENDED` via MQ to organizer + participant |
| Full suite re-run on `d458c35` | **6/6 PASS** in 29.7m — auto-end observed 64s after the end boundary (one 60s sweep tick). Single clean green run covering all 12 acceptance items |
| Leak scan | 0 hits: no password substring in any Playwright log or `test-results` artifact (greped for both values) |
| Residual data | `stg_*`/`e2e_probe_01` accounts remain (no delete-user API); 0 `ACTIVE` `STG*` meetings left behind — suite deletes/ends/cancels what it creates |
| `MeetingLifecycleIT` streak | fix `34f7f96` + 8 consecutive green CI runs including both runs on `d458c35` (`35190547528` push, `35190553892` PR) |

### First Actions-driven chain after the PR #9 merge (2026-09-17)

PR #9 merged as `77d3ecf`; the full chain then ran on real infrastructure:

| Item | Result |
| --- | --- |
| CI on `main` (`77d3ecf`) | run [35197667415](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35197667415) — all 4 jobs success |
| `Deploy Staging` | run [35198019177](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35198019177) — `workflow_run` trigger, environment approval granted, deployed tag `77d3ecfc…c10cb10c`, host reported **healthy** |
| `E2E Staging` auto-trigger | run [35198256203](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35198256203) — confirmed: fired automatically via `workflow_run` after the successful deploy, environment approval granted, SSH tunnel + stack reachable (UI 200 / API 401) |
| Suite result | **4/6, 2 failed** — `meeting lifecycle` and `staging-smoke`, each after 3 attempts |

**Failure analysis — a real test-side timezone bug, not a product bug.** Both
failures were `POST /api/meetings` never being sent: the create form blocked
submit with `开始时间不能早于当前时间`. Root cause: `fmtPicker` formatted the
`Date` via Node's *local* getters, but `playwright.config.ts` pins the browser
to `timezoneId: 'Asia/Shanghai'` and the app labels the picked wall clock
`+08:00` (`toBeijingIso`). On the UTC GitHub runner the typed string was the
UTC wall clock, which the picker parsed as Beijing time — hours in the past.
Locally invisible because the dev machine is already UTC+8; the two API-driven
tests and the UI flows that do not type a datetime were unaffected.

**Fix** (`0e6bf3d`, PR #10): `fmtPicker` now formats via `beijingIso` — a
Beijing wall clock on any runner TZ. Verified under runner-equivalent
conditions: the two failed tests were re-run locally against live staging
through the tunnel with `TZ=UTC` on the Node side — **2/2 passed** (lifecycle
50.6s, smoke 1.4m). A `workflow_dispatch` verification on the fix branch is
not possible: the `staging` environment's branch policy allows `main` only —
correct protection, not bypassed. A green 6/6 Actions run follows once PR #10
merges and the deploy chain re-triggers.

### Post-fix chain: deploy `76527bd` — full green (2026-09-17)

PR #10 merged as `76527bd`; the automated chain then ran end to end on real
infrastructure with no manual steps beyond the two environment approvals:

| Stage | Run | Result |
| --- | --- | --- |
| CI on `main` (`76527bd`) | [35206835640](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35206835640) | 4/4 jobs success |
| `Deploy Staging` | [35207238469](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35207238469) | `workflow_run` trigger, environment approval granted, tag `76527bd1…d1e45d6c8` deployed, host healthy; all 5 staging containers `healthy` (verified on-host) |
| `E2E Staging` | [35207472106](https://github.com/niuyeyeplus/RoomFlow/actions/runs/35207472106) | auto-triggered via `workflow_run`, environment approval granted, **6/6 passed in 21.6m** |

Per-test results (run `35207472106`, GitHub-hosted `ubuntu-latest` runner —
Ubuntu 24.04 image `ubuntu24/20260907.300`, UTC; the same runner family that
exposed the TZ bug): `admin room management` 13.8s, `normal user rejected`
6.6s, `meeting lifecycle` 17.9s, `participant flows` 38.4s,
`real-time lifecycle` 19.9m (auto-end observed 19s after the end boundary on
the real 60s sweep), `staging-smoke` 18.3s.

Leak scan of run `35207472106`: 0 JWT/private-key hits in the full job log,
every secret echo masked as `***` by Actions, and **no artifacts were
uploaded** (the upload step is `if: failure()` only), so nothing retained can
carry a credential.

This run is the authoritative acceptance result for the expanded staging
suite: all 12 acceptance items green in a single automated Actions chain on
the real staging stack.
