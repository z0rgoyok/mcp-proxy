#!/bin/sh
set -eu

UPSTREAM_PROXY_CA_CERT_PATH="${MCP_PROXY_CA_CERT_PATH:-/run/upstream-proxy/ca.pem}"

if [ -f "$UPSTREAM_PROXY_CA_CERT_PATH" ]; then
    cp "$UPSTREAM_PROXY_CA_CERT_PATH" /usr/local/share/ca-certificates/upstream-proxy.crt
    update-ca-certificates >/dev/null
    keytool -delete -alias upstream-proxy -cacerts -storepass changeit >/dev/null 2>&1 || true
    keytool -importcert \
        -noprompt \
        -trustcacerts \
        -alias upstream-proxy \
        -file "$UPSTREAM_PROXY_CA_CERT_PATH" \
        -cacerts \
        -storepass changeit >/dev/null
fi

exec /app/bin/mcp-proxy "$@"
