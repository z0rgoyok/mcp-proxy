#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-mcp-proxy:local}"
mkdir -p "${ROOT_DIR}/tmp"
CONTEXT_DIR="$(mktemp -d "${ROOT_DIR}/tmp/docker-runtime-context.XXXXXX")"

cleanup() {
    rm -rf "${CONTEXT_DIR}"
}
trap cleanup EXIT

cd "${ROOT_DIR}"

./gradlew --no-daemon installDist

mkdir -p "${CONTEXT_DIR}"
cp -R "${ROOT_DIR}/build/install/mcp-proxy" "${CONTEXT_DIR}/app"
cp -R "${ROOT_DIR}/scenarios" "${CONTEXT_DIR}/scenarios"
cp -R "${ROOT_DIR}/fixtures" "${CONTEXT_DIR}/fixtures"
cp "${ROOT_DIR}/README.md" "${ROOT_DIR}/RUNBOOK.md" "${ROOT_DIR}/docker-entrypoint.sh" "${CONTEXT_DIR}/"

cat > "${CONTEXT_DIR}/Dockerfile" <<'DOCKERFILE'
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl openssl \
    && rm -rf /var/lib/apt/lists/*
COPY app /app
COPY scenarios /app/scenarios
COPY fixtures /app/fixtures
COPY README.md RUNBOOK.md /app/
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh
VOLUME ["/app/var"]
EXPOSE 18081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
DOCKERFILE

docker build -t "${IMAGE_NAME}" "${CONTEXT_DIR}"
