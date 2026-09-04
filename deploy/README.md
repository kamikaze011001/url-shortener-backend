# Deploying

The host is shared. A Telegram bot owns `:8081`, a GitHub Actions runner lives here, and
the Cloudflare tunnel already carries two hostnames for other things. Every choice in
this directory that looks over-careful is that fact.

## What runs where

| | Port | Notes |
|---|---|---|
| Postgres | 5432 | loopback; own role, database **and** schema |
| Redis | **6380** | a dedicated instance, not a database index |
| Application | **8090** | loopback only — see the warning below |
| Caddy | **8088** | one listener, two vhosts by `Host` header |

**The application must never bind to a public interface.** `ClientRequest` trusts
`CF-Connecting-IP` without verifying it, on the stated grounds that the process is
reachable only through the tunnel. Reachable directly, that header is spoofable by
anyone and every per-IP limit in FR-6 becomes decoration. `SERVER_ADDRESS=127.0.0.1` in
the unit file is what holds that promise.

## Isolation

The instruction was to keep this application's state to itself, and stateful services
are installed on the host rather than in containers.

**Postgres — three walls.** A role with no `SUPERUSER`, `CREATEDB` or `CREATEROLE`; a
database with `CONNECT` revoked from `PUBLIC`; and a named schema, because `public` is
writable by every role in a stock cluster and the next application here will have one
too. `search_path` is set on the role in that database, so nothing in the application
has to know the schema's name beyond one JDBC parameter.

**Redis — a separate process, not a `SELECT` index.** An index is a namespace, not a
boundary: every index on a server shares one memory ceiling, one eviction policy and one
lifetime, so a neighbour can evict this application's keys and a single flush takes out
every tenant at once. A second `redis-server` costs about 10 MB and makes each of those
someone else's problem. Ubuntu's packaged instance on 6379 is disabled by
`provision.sh`, because an unowned service on a port somebody will want is a trap.

## Configuration

Everything host-specific lives in `deploy/deploy.env`, which is **git-ignored**:

```bash
cp deploy/deploy.env.example deploy/deploy.env
$EDITOR deploy/deploy.env     # DEPLOY_HOST, TUNNEL_ID, APP_HOSTNAME, SHORT_HOSTNAME
```

None of it is secret. It is kept out of the repository anyway, because a public
repository that names a host, an SSH endpoint and the account to reach it with has
published a map — and a map is useful to somebody even when every lock on it holds.

## Order of operations

```bash
source deploy/deploy.env      # for $DEPLOY_HOST below

# 1 — on a workstation, from the backend repo
scp -r deploy "$DEPLOY_HOST:/tmp/url-shortener-deploy"

# 2 — on the server, once. Installs packages, creates the role/db/schema,
#     the Redis instance, the unit and the Caddy config.
ssh -t "$DEPLOY_HOST" 'sudo bash /tmp/url-shortener-deploy/provision.sh'

# 3 — fill in the three blank mail lines
ssh -t "$DEPLOY_HOST" 'sudo nano /opt/url-shortener/.env'

# 4 — DNS. Run as the login user: the tunnel certificate lives in ~/.cloudflared,
#     so this needs no sudo.
ssh "$DEPLOY_HOST" \
  "cloudflared tunnel route dns $TUNNEL_ID $APP_HOSTNAME && \
   cloudflared tunnel route dns $TUNNEL_ID $SHORT_HOSTNAME"

# 5 — build and upload both halves, then install, add ingress and start.
#     finish.sh INSERTS two rules into whatever ingress the tunnel already has,
#     rather than replacing the file: a tunnel commonly carries an SSH entry
#     point among its rules, and that is how you would reach the box to undo a
#     mistake. It backs up and validates before restarting cloudflared.
bash deploy/ship.sh
ssh -t "$DEPLOY_HOST" 'sudo bash /tmp/url-shortener-deploy/finish.sh'

# 6 — optional: run the same jar as a container instead (ADR-0021)
ssh -t "$DEPLOY_HOST" 'sudo bash /tmp/url-shortener-deploy/containerize.sh'
```

## Verifying

```bash
source deploy/deploy.env
python3 e2e/run.py --base "https://$APP_HOSTNAME" \
                   --short-base "https://$SHORT_HOSTNAME" \
                   --account 'you@example.com:your-password'
```

`--account` adopts an existing, already-verified Owner instead of registering one.
Production has no Mailpit to read a code from, so the four checks that genuinely need
an inbox are **skipped and listed as not verified** at the end of the run, rather than
failing for a reason that has nothing to do with the system. 101 of the 113 checks
still run. It never completes a password reset in this mode: that would change the
password of an account somebody handed us.

Two things this suite cannot check locally and *can* check here, both in scenario 9:
that `CF-Connecting-IP` survives the tunnel and Caddy to reach the rate limiter
(FR-6.6), and that the two-hostname split behaves (ADR-0006). If the per-IP limit never
engages in production, the header is not arriving and every limit in FR-6 is counting
one shared address.

## Redeploying

`ship.sh` again. It rebuilds, uploads to a staging directory, installs and restarts —
a few seconds of downtime while the JVM starts, during which the SPA still serves and
short links answer 502.

`provision.sh` does not need re-running, and re-running it is safe: it regenerates no
secrets and rewrites no `.env`. That is on purpose. A regenerated `JWT_SECRET` signs
every user out; a regenerated `CLICK_HASH_SALT` silently splits every visitor's Click
history in two.

## Rolling back

The previous jar is not kept — this is a demo, and the source of truth is a git tag.
Check out the tag, run `ship.sh`. Flyway migrations do not roll back, so a rollback
across a migration needs the schema dealt with by hand.
