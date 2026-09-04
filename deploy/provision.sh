#!/usr/bin/env bash
#
# Provisions the host for the URL Shortener. Run as root, from the directory this file
# lives in. Idempotent: running it twice changes nothing the second time, which matters
# because the first run of anything like this is never the only run.
#
#     sudo bash provision.sh
#
# It deliberately does NOT deploy the application artifacts — ship.sh does that, and
# keeping the two apart means a redeploy never risks re-running a migration of the
# operating system.
#
# This host is shared with a Telegram bot on :8081 and a GitHub Actions runner. Every
# choice below that looks over-careful is that fact.

set -euo pipefail

APP=url-shortener
APP_DIR=/opt/${APP}
DB_NAME=urlshortener
DB_USER=urlshortener
DB_SCHEMA=urlshortener
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
	echo "provision.sh must run as root: sudo bash provision.sh" >&2
	exit 1
fi

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

# ── deployment parameters ────────────────────────────────────────────────────
# Hostnames, ports and the target host live in deploy.env, which is git-ignored. A public
# repository that names a host, an SSH endpoint and the account to reach it with has
# published a map, and a map is useful to somebody even when every lock on it holds.
ENV_SRC="${HERE}/deploy.env"
if [[ ! -f ${ENV_SRC} ]]; then
	echo "Missing ${ENV_SRC}. Copy deploy.env.example to deploy.env and fill it in." >&2
	exit 1
fi
# shellcheck disable=SC1090
source "${ENV_SRC}"
: "${APP_HOSTNAME:?set APP_HOSTNAME in deploy.env}"
: "${SHORT_HOSTNAME:?set SHORT_HOSTNAME in deploy.env}"
: "${APP_PORT:=8090}"
: "${CADDY_PORT:=8088}"
: "${REDIS_PORT:=6380}"

render_caddyfile() {
	# Substitution rather than Caddy's own {$ENV} syntax: that would need the values in
	# caddy.service's environment, which is one more file to keep in step with this one.
	sed -e "s|__APP_HOSTNAME__|${APP_HOSTNAME}|g" \
	    -e "s|__SHORT_HOSTNAME__|${SHORT_HOSTNAME}|g" \
	    -e "s|__APP_PORT__|${APP_PORT}|g" \
	    -e "s|__CADDY_PORT__|${CADDY_PORT}|g" \
	    "$1" >"$2"
}


# ── packages ─────────────────────────────────────────────────────────────────
# All four are in Ubuntu 24.04's own repositories, so there is no third-party apt
# source to trust, pin or explain later.
say "Packages"
export DEBIAN_FRONTEND=noninteractive

# Checked first, and with a timeout, because the failure mode otherwise is a silent
# hang: `apt-get update` retries a dead mirror for minutes with no output, and the only
# visible symptom is a script that has printed "Packages" and stopped. This host has
# seen exactly that — plain HTTP to archive.ubuntu.com blackholes while HTTPS to the
# same host answers in a second.
for host in $(grep -hoE 'https?://[^ ]+' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null | sort -u); do
	if ! timeout 15 curl -sfI -o /dev/null "${host%/}/dists/$(lsb_release -cs)/Release" 2>/dev/null; then
		cat >&2 <<UNREACHABLE

  Cannot reach ${host} within 15 seconds.

  apt would hang here rather than fail. If this is a plain http:// URI, try https://
  for the same host — some networks blackhole port 80 to the Ubuntu archive while
  leaving 443 alone:

    sudo sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources

UNREACHABLE
		exit 1
	fi
done

# -q, not -qq. The quieter form prints nothing at all, which makes a perfectly normal
# two-minute download indistinguishable from a hang — and that is not a distinction to
# leave to the person watching.
apt-get update -q
apt-get install -y -q \
	postgresql postgresql-contrib \
	redis-server \
	openjdk-21-jre-headless \
	caddy

# Ubuntu's redis-server package starts a default instance on 6379. This deployment does
# not use it, and leaving it running would be a service nobody owns listening on a port
# somebody will eventually want. Ours is a separate unit on 6380.
if systemctl is-enabled --quiet redis-server 2>/dev/null; then
	say "Stopping the packaged Redis on 6379 — this deployment uses its own on ${REDIS_PORT}"
	systemctl disable --now redis-server
fi

# ── service account ──────────────────────────────────────────────────────────
say "Service account and directories"
if ! id -u ${APP} >/dev/null 2>&1; then
	useradd --system --home-dir ${APP_DIR} --shell /usr/sbin/nologin ${APP}
fi

install -d -o ${APP} -g ${APP} -m 0755 ${APP_DIR}
install -d -o ${APP} -g ${APP} -m 0755 ${APP_DIR}/frontend
install -d -o ${APP} -g ${APP} -m 0755 ${APP_DIR}/short-root
install -d -o ${APP} -g ${APP} -m 0750 ${APP_DIR}/logs

# ── postgres ─────────────────────────────────────────────────────────────────
# Three walls, not one. A role that owns only its own database; a database nobody else
# may even connect to; and a named schema inside it, because `public` is writable by
# every role by default and the next application on this cluster will have one too.
say "Postgres role, database and schema"
systemctl enable --now postgresql

DB_PASSWORD_FILE=${APP_DIR}/.dbpass
if [[ ! -f ${DB_PASSWORD_FILE} ]]; then
	# Generated here and never printed. The only copy lives in two 0600 files on this
	# host — this one and .env — so it cannot leak through a shell history or a paste.
	openssl rand -base64 32 | tr -d '\n/+=' | head -c 40 >${DB_PASSWORD_FILE}
	chown ${APP}:${APP} ${DB_PASSWORD_FILE}
	chmod 0600 ${DB_PASSWORD_FILE}
fi
DB_PASSWORD="$(cat ${DB_PASSWORD_FILE})"

sudo -u postgres psql -v ON_ERROR_STOP=1 --quiet <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_USER}') THEN
        -- No SUPERUSER, no CREATEDB, no CREATEROLE. This role can do exactly one thing.
        CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}';
    ELSE
        ALTER ROLE ${DB_USER} PASSWORD '${DB_PASSWORD}';
    END IF;
END
\$\$;
SQL

if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
	sudo -u postgres createdb -O ${DB_USER} ${DB_NAME}
fi

sudo -u postgres psql -v ON_ERROR_STOP=1 --quiet -d ${DB_NAME} <<SQL
-- Nobody but this role gets in, not even to look at the catalogue.
REVOKE CONNECT ON DATABASE ${DB_NAME} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${DB_NAME} TO ${DB_USER};

-- `public` is writable by every role in a stock Postgres. Closing it and using a named
-- schema is what stops a future tenant on this cluster from writing into our tables by
-- accident, or reading them on purpose.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS ${DB_SCHEMA} AUTHORIZATION ${DB_USER};
ALTER ROLE ${DB_USER} IN DATABASE ${DB_NAME} SET search_path TO ${DB_SCHEMA};
SQL

# ── redis ────────────────────────────────────────────────────────────────────
say "Dedicated Redis on ${REDIS_PORT}"
install -d -o redis -g redis -m 0750 /var/lib/redis-urlshortener
install -o redis -g redis -m 0640 "${HERE}/redis-urlshortener.conf" /etc/redis/redis-urlshortener.conf

cat >/etc/systemd/system/redis-urlshortener.service <<'UNIT'
[Unit]
Description=Redis for the URL Shortener
After=network-online.target
Wants=network-online.target

[Service]
Type=notify
ExecStart=/usr/bin/redis-server /etc/redis/redis-urlshortener.conf --supervised systemd
User=redis
Group=redis
RuntimeDirectory=redis-urlshortener
RuntimeDirectoryMode=0755
Restart=on-failure
MemoryMax=384M
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/lib/redis-urlshortener /var/log/redis

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now redis-urlshortener

# ── application environment ──────────────────────────────────────────────────
# Written once with generated secrets and mail left blank. Regenerating JWT_SECRET on
# every run would sign every user out on every deploy, and regenerating CLICK_HASH_SALT
# would silently split each visitor's Click history in two.
say "Application environment"
ENV_FILE=${APP_DIR}/.env
if [[ ! -f ${ENV_FILE} ]]; then
	cat >${ENV_FILE} <<ENV
# Generated by provision.sh. Secrets: keep this file at 0600.

SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8090

DB_URL=jdbc:postgresql://127.0.0.1:5432/${DB_NAME}?currentSchema=${DB_SCHEMA}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
SPRING_FLYWAY_SCHEMAS=${DB_SCHEMA}

REDIS_HOST=127.0.0.1
REDIS_PORT=${REDIS_PORT}

# The single most important line here. Derived from a request or left at the default,
# the first real visitor is handed a localhost URL that works on nobody's machine.
SHORT_BASE_URL=https://${SHORT_HOSTNAME}

JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
CLICK_HASH_SALT=$(openssl rand -base64 32 | tr -d '\n')

# ── fill these in ────────────────────────────────────────────────────────────
# Gmail with an app password, not the account password. MAIL_FROM must be the same
# address as MAIL_USERNAME: Gmail rewrites or rejects a From it does not own, and the
# failure is silent enough to look like a code that never sent.
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_AUTH=true
MAIL_STARTTLS=true
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=
ENV
	chown ${APP}:${APP} ${ENV_FILE}
	chmod 0600 ${ENV_FILE}
	echo "  wrote ${ENV_FILE} — MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM are still blank"
else
	echo "  ${ENV_FILE} exists; leaving it alone (secrets are not regenerated)"
fi

# ── units and web server ─────────────────────────────────────────────────────
say "systemd unit and Caddy"
install -m 0644 "${HERE}/url-shortener.service" /etc/systemd/system/url-shortener.service

install -d -o caddy -g caddy -m 0755 /var/log/caddy

# Validated from the source path, BEFORE it is installed. Validating afterwards would
# abort this script with a broken config already sitting in /etc/caddy, which is the
# worst of both outcomes.
render_caddyfile "${HERE}/Caddyfile.template" /tmp/Caddyfile.rendered
caddy validate --config /tmp/Caddyfile.rendered --adapter caddyfile

if [[ -f /etc/caddy/Caddyfile && ! -f /etc/caddy/Caddyfile.before-url-shortener ]]; then
	cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-url-shortener
fi
install -m 0644 /tmp/Caddyfile.rendered /etc/caddy/Caddyfile

# A holding page, so the short host answers something sane at / before anyone asks.
if [[ ! -f ${APP_DIR}/short-root/index.html ]]; then
	cat >${APP_DIR}/short-root/index.html <<'HTML'
<!doctype html><meta charset=utf-8><title>Switchboard</title>
<body style="font-family:system-ui;text-align:center;padding:4rem">
<h1>Switchboard</h1><p>This host serves short links only.</p>
HTML
	chown ${APP}:${APP} ${APP_DIR}/short-root/index.html
fi

systemctl daemon-reload
systemctl enable caddy
systemctl restart caddy

say "Provisioned"
cat <<NEXT

  Postgres   ${DB_NAME}.${DB_SCHEMA} as ${DB_USER}, loopback only
  Redis      127.0.0.1:${REDIS_PORT}, capped at 256mb, no persistence
  Caddy      :${CADDY_PORT}, vhosts for ${APP_HOSTNAME} and ${SHORT_HOSTNAME}
  App        not started yet — it has no jar

  Still to do, in order:
    1. fill MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM in ${ENV_FILE}
    2. run ship.sh from a workstation to build and upload the artifacts
    3. add the two ingress rules and DNS records
    4. systemctl start ${APP}
NEXT
