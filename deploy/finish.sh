#!/usr/bin/env bash
#
# The privileged half of a first deploy: installs the artifacts staged by ship.sh,
# updates the tunnel ingress, and starts the service.
#
#     sudo bash finish.sh
#
# Separate from provision.sh because provisioning is a once-per-host operation and this
# is a once-per-release one. Idempotent, and safe to re-run.

set -euo pipefail

APP=url-shortener
APP_DIR=/opt/${APP}
STAGING=/tmp/url-shortener-ship
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
	echo "finish.sh must run as root: sudo bash finish.sh" >&2
	exit 1
fi

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  ! %s\033[0m\n' "$*"; }

ENV_SRC="${HERE}/deploy.env"
[[ -f ${ENV_SRC} ]] || { echo "Missing ${ENV_SRC}; copy deploy.env.example" >&2; exit 1; }
# shellcheck disable=SC1090
source "${ENV_SRC}"
: "${APP_HOSTNAME:?set APP_HOSTNAME in deploy.env}"
: "${SHORT_HOSTNAME:?set SHORT_HOSTNAME in deploy.env}"
: "${TUNNEL_ID:?set TUNNEL_ID in deploy.env}"
: "${APP_PORT:=8090}"
: "${CADDY_PORT:=8088}"

render_caddyfile() {
	sed -e "s|__APP_HOSTNAME__|${APP_HOSTNAME}|g" \
	    -e "s|__SHORT_HOSTNAME__|${SHORT_HOSTNAME}|g" \
	    -e "s|__APP_PORT__|${APP_PORT}|g" \
	    -e "s|__CADDY_PORT__|${CADDY_PORT}|g" \
	    "$1" >"$2"
}

[[ -f ${STAGING}/app.jar ]] || { echo "No ${STAGING}/app.jar — run ship.sh first" >&2; exit 1; }

# ── caddy ────────────────────────────────────────────────────────────────────
say "Caddy"
# Validated from the source path before it replaces anything, so a bad config cannot be
# left behind by an aborted run.
render_caddyfile "${HERE}/Caddyfile.template" /tmp/Caddyfile.rendered
caddy validate --config /tmp/Caddyfile.rendered --adapter caddyfile >/dev/null 2>&1
install -m 0644 /tmp/Caddyfile.rendered /etc/caddy/Caddyfile
# restart, not reload. `systemctl reload caddy` shells out to `caddy reload`, which
# pushes the new config through the admin API — and this Caddyfile turns the admin API
# off. Reload can therefore never work here, so asking for it just prints a failure and
# falls through to the restart that was always going to happen.
systemctl restart caddy
echo "  bound to loopback: $(ss -tln | grep -c "127.0.0.1:${CADDY_PORT}") listener(s) on 127.0.0.1:${CADDY_PORT}"

# ── artifacts ────────────────────────────────────────────────────────────────
say "Application artifacts"
install -m 0644 "${HERE}/url-shortener.service" /etc/systemd/system/url-shortener.service
systemctl daemon-reload
install -o ${APP} -g ${APP} -m 0644 ${STAGING}/app.jar ${APP_DIR}/app.jar
rm -rf ${APP_DIR}/frontend
cp -r ${STAGING}/frontend ${APP_DIR}/frontend
chown -R ${APP}:${APP} ${APP_DIR}/frontend
echo "  $(du -h ${APP_DIR}/app.jar | cut -f1) jar, $(find ${APP_DIR}/frontend -type f | wc -l) frontend files"

# ── tunnel ingress ───────────────────────────────────────────────────────────
# The riskiest step in this file. A tunnel commonly carries an SSH entry point among its
# rules, so the config is backed up first and validated BEFORE cloudflared is asked to
# load it — the way you would reach the host to fix a mistake may be one of the rules.
say "Tunnel ingress"
# Our two rules are INSERTED into whatever config the tunnel already has, immediately
# before the catch-all. Earlier versions of this script shipped a whole config file with
# every rule reproduced in it — which meant this repository carried a list of every other
# hostname the tunnel served, including an SSH entry point, for anyone who cared to read
# it. Inserting also means a rule added by somebody else next week survives a redeploy.
python3 - "$APP_HOSTNAME" "$SHORT_HOSTNAME" "$CADDY_PORT" <<'INSERT'
import re, shutil, sys, time

app, short, port = sys.argv[1], sys.argv[2], sys.argv[3]
path = "/etc/cloudflared/config.yml"
original = open(path).read()

if app in original and short in original:
    print("  already present")
    raise SystemExit(0)

shutil.copy(path, f"{path}.{time.strftime('%Y%m%d-%H%M%S')}.bak")

block = "".join(
    f"  - hostname: {h}\n    service: http://127.0.0.1:{port}\n" for h in (app, short)
)

# Before the catch-all: an entry with no hostname matches everything, so anything below
# it is unreachable. If there is no catch-all, append and let cloudflared's own default
# 404 do the job.
catch_all = re.search(r"^\s*-\s+service:\s+http_status:404\s*$", original, re.M)
if catch_all:
    updated = original[: catch_all.start()] + block + original[catch_all.start() :]
else:
    updated = original.rstrip() + "\n" + block

open(path, "w").write(updated)
print(f"  inserted 2 rules before the catch-all (previous config kept as .bak)")
INSERT

# `--config` belongs BEFORE the subcommand. With it after, cloudflared prints "flag
# provided but not defined" and then exits 0, so set -e does not catch it and an
# unvalidated config reaches a tunnel that may carry this host's SSH hostname. Grepping
# for OK rather than trusting an exit status that has already lied once.
if ! cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate 2>&1 | tee /dev/stderr | grep -q '^OK$'; then
	echo "  ingress did NOT validate — restoring the previous config" >&2
	cp "$(ls -t /etc/cloudflared/config.yml.*.bak | head -1)" /etc/cloudflared/config.yml
	exit 1
fi
systemctl restart cloudflared

# ── the application ──────────────────────────────────────────────────────────
say "Starting ${APP}"
if ! grep -q '^MAIL_USERNAME=.\+' ${APP_DIR}/.env; then
	warn "MAIL_USERNAME is blank in ${APP_DIR}/.env — the app will start, but no"
	warn "verification or password-reset code will ever be delivered."
fi

systemctl enable ${APP} >/dev/null 2>&1
# Clears any failed/start-limit state left by a previous bad run. Without it, a unit that
# has flapped past its burst limit refuses to start at all, and the error names the rate
# limiter rather than whatever originally broke — which sends you looking somewhere else
# entirely.
systemctl reset-failed ${APP} 2>/dev/null || true
systemctl restart ${APP}

# Flyway runs eight migrations against an empty database on this first start, so give it
# more room than a restart would need before calling it a failure.
for i in $(seq 1 40); do
	if curl -sf -o /dev/null http://127.0.0.1:${APP_PORT}/actuator/health 2>/dev/null; then
		echo "  healthy after ${i}s"
		break
	fi
	sleep 1
done

say "State"
for s in postgresql redis-urlshortener caddy cloudflared ${APP}; do
	printf '  %-22s %s\n' "$s" "$(systemctl is-active $s)"
done

echo
curl -s -o /dev/null -w "  app health          %{http_code}\n" http://127.0.0.1:${APP_PORT}/actuator/health || true
curl -s -o /dev/null -w "  dashboard via caddy  %{http_code}\n" -H "Host: ${APP_HOSTNAME}" http://127.0.0.1:${CADDY_PORT}/ || true
curl -s -o /dev/null -w "  short host via caddy %{http_code}\n" -H "Host: ${SHORT_HOSTNAME}" http://127.0.0.1:${CADDY_PORT}/ || true

if ! systemctl is-active --quiet ${APP}; then
	echo
	echo "  ${APP} is not running. Last 30 log lines:"
	journalctl -u ${APP} -n 30 --no-pager
	exit 1
fi
