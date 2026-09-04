#!/usr/bin/env bash
#
# Builds both halves on a workstation and uploads them. Run from the backend repo:
#
#     bash deploy/ship.sh
#
# Building here rather than on the server is deliberate. The server would otherwise need
# a JDK, a Node toolchain and a checkout of two repositories, all of which are a second
# environment that can drift from the one the code was tested in. What lands there is a
# jar and a directory of static files, and nothing else has to be true.
#
# The restart is last and is the only moment of downtime: a few seconds while the JVM
# starts, during which Caddy still serves the SPA and short links answer 502.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ ! -f ${HERE}/deploy.env ]]; then
	echo "Missing ${HERE}/deploy.env. Copy deploy.env.example and fill it in." >&2
	exit 1
fi
# shellcheck disable=SC1090
source "${HERE}/deploy.env"
HOST=${DEPLOY_HOST:?set DEPLOY_HOST in deploy.env}
APP_DIR=/opt/url-shortener
FRONTEND=${FRONTEND:-../url-shortener-frontend}
STAGING=/tmp/url-shortener-ship

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

say "Building the backend"
./gradlew bootJar -q
JAR=$(ls -1 build/libs/*.jar | grep -v plain | head -1)
echo "  ${JAR}  ($(du -h "${JAR}" | cut -f1))"

say "Building the frontend"
(cd "${FRONTEND}" && npm run build >/dev/null && echo "  $(du -sh dist | cut -f1) in dist/")

say "Uploading"
# Into a staging directory first. The service user's home is not writable by the SSH
# user, and a half-uploaded jar in place is worse than no jar at all.
ssh "${HOST}" "rm -rf ${STAGING} && mkdir -p ${STAGING}/frontend"
scp -q "${JAR}" "${HOST}:${STAGING}/app.jar"
scp -qr "${FRONTEND}/dist/." "${HOST}:${STAGING}/frontend/"

say "Installing and restarting (sudo password will be prompted)"
ssh -t "${HOST}" "sudo install -o urlshortener -g urlshortener -m 0644 ${STAGING}/app.jar ${APP_DIR}/app.jar \
	&& sudo rm -rf ${APP_DIR}/frontend \
	&& sudo cp -r ${STAGING}/frontend ${APP_DIR}/frontend \
	&& sudo chown -R urlshortener:urlshortener ${APP_DIR}/frontend \
	&& sudo systemctl restart url-shortener \
	&& sleep 6 \
	&& systemctl is-active url-shortener \
	&& curl -sf -o /dev/null -w 'health %{http_code}\n' http://127.0.0.1:8090/actuator/health"

say "Shipped"
