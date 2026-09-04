#!/usr/bin/env bash
#
# Switches the running service from a bare jar under systemd to the same jar inside a
# container. Run as root, after ship.sh has staged app.jar.
#
#     sudo bash containerize.sh
#
# The jar-based unit is kept, disabled, beside the new one. Rolling back is one command
# and needs nothing rebuilt — which is the point of doing the switch this way rather
# than editing the unit in place.
#
# If the container does not come up healthy, this script puts the jar unit back by
# itself. A cutover that leaves the service down while someone reads an error message is
# a worse outcome than not having cut over.

set -euo pipefail

APP=url-shortener
APP_DIR=/opt/${APP}
UNIT=/etc/systemd/system/${APP}.service
BACKUP=/etc/systemd/system/${APP}.service.jar
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_SRC="${HERE}/deploy.env"
[[ -f ${ENV_SRC} ]] || { echo "Missing ${ENV_SRC}; copy deploy.env.example" >&2; exit 1; }
# shellcheck disable=SC1090
source "${ENV_SRC}"
: "${APP_HOSTNAME:?set APP_HOSTNAME in deploy.env}"
: "${APP_PORT:=8090}"
: "${CADDY_PORT:=8088}"

[[ $EUID -eq 0 ]] || { echo "run as root: sudo bash containerize.sh" >&2; exit 1; }
[[ -f ${APP_DIR}/app.jar ]] || { echo "No ${APP_DIR}/app.jar — run ship.sh first" >&2; exit 1; }

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

healthy() {
	for _ in $(seq 1 40); do
		curl -sf -o /dev/null http://127.0.0.1:${APP_PORT}/actuator/health 2>/dev/null && return 0
		sleep 1
	done
	return 1
}

# ── the environment file means something different to docker ─────────────────
# systemd's EnvironmentFile strips surrounding quotes from a value; docker's --env-file
# does not, and passes them through as part of the string. A password typed into an
# editor as "abcd efgh" therefore works today and would start failing SMTP authentication
# the moment it moved into a container — as a mail problem, nowhere near the change that
# caused it. Checked before the cutover rather than debugged after it.
say "Environment file"
if grep -qE '^[A-Za-z_][A-Za-z0-9_]*=["'"'"']' ${APP_DIR}/.env; then
	echo >&2
	echo "  Quoted values found in ${APP_DIR}/.env:" >&2
	grep -nE '^[A-Za-z_][A-Za-z0-9_]*=["'"'"']' ${APP_DIR}/.env | sed 's/=.*/=<redacted>/' >&2
	echo >&2
	echo "  systemd strips those quotes; docker keeps them. Remove the quotes and" >&2
	echo "  re-run — values with spaces need no quoting in either format." >&2
	exit 1
fi
echo "  $(grep -cE '^[A-Za-z_][A-Za-z0-9_]*=' ${APP_DIR}/.env) variables, no quoting to trip over"

# ── build ────────────────────────────────────────────────────────────────────
# On the server, not on a workstation: the jar is architecture-independent but the image
# is not, and building here produces one native to the host that runs it instead of a
# cross-build under emulation.
say "Building the image"
BUILD=$(mktemp -d)
cp "${HERE}/Dockerfile" "${BUILD}/Dockerfile"
cp "${APP_DIR}/app.jar" "${BUILD}/app.jar"
docker build -q -t ${APP}:current "${BUILD}"
rm -rf "${BUILD}"

# Tagged with the jar's checksum as well as :current, so a rollback has something to
# name. :current is a moving pointer; this is the immutable one.
DIGEST=$(sha256sum ${APP_DIR}/app.jar | cut -c1-12)
docker tag ${APP}:current ${APP}:${DIGEST}
echo "  ${APP}:current and ${APP}:${DIGEST}  ($(docker images ${APP}:current --format '{{.Size}}'))"

# ── keep a way back ──────────────────────────────────────────────────────────
say "Preserving the jar unit"
if [[ ! -f ${BACKUP} ]]; then
	cp ${UNIT} ${BACKUP}
	echo "  kept at ${BACKUP}"
else
	echo "  already kept at ${BACKUP}"
fi

# ── cut over ─────────────────────────────────────────────────────────────────
say "Switching to the container"
systemctl stop ${APP}
install -m 0644 "${HERE}/url-shortener-container.service" ${UNIT}
systemctl daemon-reload
systemctl reset-failed ${APP} 2>/dev/null || true

if systemctl start ${APP} && healthy; then
	say "Running as a container"
	docker ps --filter name=${APP} --format '  {{.Image}}  {{.Status}}  {{.Names}}'
	curl -s -o /dev/null -w "  app health           %{http_code}\n" http://127.0.0.1:${APP_PORT}/actuator/health
	curl -s -o /dev/null -w "  dashboard via caddy  %{http_code}\n" -H "Host: ${APP_HOSTNAME}" http://127.0.0.1:${CADDY_PORT}/
	echo
	echo "  roll back with:  sudo cp ${BACKUP} ${UNIT} && sudo systemctl daemon-reload && sudo systemctl restart ${APP}"
else
	say "Container did not come up — rolling back to the jar"
	journalctl -u ${APP} -n 30 --no-pager || true
	systemctl stop ${APP} 2>/dev/null || true
	cp ${BACKUP} ${UNIT}
	systemctl daemon-reload
	systemctl reset-failed ${APP} 2>/dev/null || true
	systemctl start ${APP}
	healthy && echo "  jar is running again, service restored" || echo "  JAR ALSO FAILED — needs a human"
	exit 1
fi
