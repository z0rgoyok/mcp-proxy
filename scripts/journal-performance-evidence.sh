#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_DIR="${EVIDENCE_DIR:-/tmp/mcp-proxy-Z0R-1190}"
EVENT_COUNT="${EVENT_COUNT:-25000}"
STATE_DIR="$EVIDENCE_DIR/state"
JOURNAL_FILE="$STATE_DIR/journal/events.jsonl"
GRADLE_LOG="$EVIDENCE_DIR/gradle-test.log"
GREP_LOG="$EVIDENCE_DIR/full-read-grep.log"
REPORT="$EVIDENCE_DIR/evidence.md"

mkdir -p "$STATE_DIR/journal"
rm -f "$JOURNAL_FILE" "$GRADLE_LOG" "$GREP_LOG" "$REPORT"

for index in $(seq 1 "$EVENT_COUNT"); do
  padded="$(printf "%05d" "$index")"
  printf '{"timestamp":"2026-04-28T09:00:00Z","method":"GET","path":"/v1/items/%s","uri":"/v1/items/%s","scenario":"demo","mode":"mock","status":200,"fixture":"items.json","requestBodyFile":null,"responseBodyFile":null,"requestBodyBytes":0,"responseBodyBytes":128}\n' "$padded" "$padded" >> "$JOURNAL_FILE"
done

line_count="$(wc -l < "$JOURNAL_FILE" | tr -d ' ')"
byte_count="$(wc -c < "$JOURNAL_FILE" | tr -d ' ')"

cd "$ROOT_DIR"

{
  echo "# Journal Performance Evidence"
  echo
  echo "- issue: Z0R-1190"
  echo "- state directory: $STATE_DIR"
  echo "- journal file: $JOURNAL_FILE"
  echo "- journal lines: $line_count"
  echo "- journal bytes: $byte_count"
  echo
  echo "## Runtime Check"
  echo
  echo '```text'
} > "$REPORT"

./gradlew --no-daemon test --tests dev.mcp.proxy.performance.JournalPerformanceEvidenceTest | tee "$GRADLE_LOG"
cat "$GRADLE_LOG" >> "$REPORT"

{
  echo '```'
  echo
  echo "## Static Regression Check"
  echo
  echo '```text'
} >> "$REPORT"

if rg -n 'Files\.readAllLines|Files\.readString\(.*events|lineSequence\(\).*toList|takeLast\(' \
  src/main/kotlin/dev/mcp/proxy/application \
  src/main/kotlin/dev/mcp/proxy/infrastructure/server \
  src/main/kotlin/dev/mcp/proxy/infrastructure/mcp > "$GREP_LOG"; then
  cat "$GREP_LOG" >> "$REPORT"
  echo '```' >> "$REPORT"
  echo "Full-read journal patterns were found. See $GREP_LOG" >&2
  exit 1
else
  echo "No full-read journal patterns found in application, server, or MCP main code." | tee "$GREP_LOG"
  cat "$GREP_LOG" >> "$REPORT"
  echo '```' >> "$REPORT"
fi

echo "Evidence written to $REPORT"
