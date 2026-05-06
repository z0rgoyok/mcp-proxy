const initialStatus = window.__MCP_PROXY_INITIAL_STATUS__;
const runtimeStats = document.getElementById("runtimeStats");
const runtimePill = document.getElementById("runtimePill");
const scenarioList = document.getElementById("scenarioList");
const stateStore = document.getElementById("stateStore");
const journalRows = document.getElementById("journalRows");
const refreshButton = document.getElementById("refreshButton");
const refreshStatus = document.getElementById("refreshStatus");
const autoRefresh = document.getElementById("autoRefresh");
let timerId = null;
let refreshInFlight = false;

function escapeHtml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}

function bodyLink(label, path) {
  if (!path) return "";
  return `<a href="${initialStatus.adminBasePath}/api/body?path=${encodeURIComponent(path)}" target="_blank" rel="noreferrer">${label}</a> `;
}

function basename(path) {
  return path ? path.split("/").pop() : "";
}

function hostname(url) {
  if (!url) return "";
  try {
    return new URL(url).hostname;
  } catch (_) {
    return url;
  }
}

function formatTime(timestamp) {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return timestamp;
  return date.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function statusClass(status) {
  if (status >= 500) return "status-err";
  if (status >= 400) return "status-warn";
  return "status-ok";
}

function markRefresh(state, text) {
  refreshStatus.dataset.state = state;
  refreshStatus.textContent = text;
}

function withCacheBuster(path) {
  const separator = path.includes("?") ? "&" : "?";
  return `${path}${separator}_=${Date.now()}`;
}

function renderStatus(status) {
  runtimePill.textContent = status.running ? "RUNNING" : "STOPPED";
  runtimePill.dataset.running = String(status.running);
  const stats = [
    ["Scenario", status.scenario || "unknown", status.scenario || "unknown", ""],
    ["Proxy port", String(status.proxyPort), String(status.proxyPort), ""],
    ["Upstream", hostname(status.upstreamBaseUrl) || "not set", status.upstreamBaseUrl || "not set", ""],
    ["State directory", status.stateDirectory, status.stateDirectory, "path"],
    ["Runtime file", basename(status.runtimeFile), status.runtimeFile, "path"],
    ["Journal file", basename(status.journalFile), status.journalFile, "path"]
  ];
  runtimeStats.innerHTML = stats.map(([label, value, title, className]) => `<div class="stat"><div class="label">${escapeHtml(label)}</div><div class="value ${className}" title="${escapeHtml(title)}">${escapeHtml(value)}</div></div>`).join("");
  scenarioList.innerHTML = status.availableScenarios.length ? status.availableScenarios.map((scenario) => `<span class="scenario-tag">${escapeHtml(scenario)}</span>`).join("") : `<span class="muted">No scenarios found</span>`;
}

function renderState(state) {
  if (!state.items.length) {
    stateStore.textContent = "State store is empty";
    return;
  }
  stateStore.textContent = state.items.map((item) => {
    try {
      return `${item.key}\n${JSON.stringify(JSON.parse(item.rawJson), null, 2)}`;
    } catch (_) {
      return `${item.key}\n${item.rawJson}`;
    }
  }).join("\n\n");
}

function networkDetails(item) {
  const parts = [];
  if (item.bodyMode) parts.push(`body=${item.bodyMode}`);
  if (item.delayMillis !== null && item.delayMillis !== undefined) parts.push(`delay=${item.delayMillis}ms`);
  if (item.timeoutMillis !== null && item.timeoutMillis !== undefined) parts.push(`timeout=${item.timeoutMillis}ms`);
  if (item.effectiveDelayMillis !== null && item.effectiveDelayMillis !== undefined) parts.push(`wait=${item.effectiveDelayMillis}ms`);
  return parts.join(" ");
}

function renderJournal(items) {
  journalRows.innerHTML = items.length
    ? items.map((item) => {
      const request = `${item.method} ${item.path}`;
      const fixture = item.fixture || "";
      const network = networkDetails(item);
      return `<tr><td class="col-time" title="${escapeHtml(item.timestamp)}">${escapeHtml(formatTime(item.timestamp))}</td><td class="col-mode">${escapeHtml(item.mode)}</td><td class="col-request" title="${escapeHtml(request)}"><span class="method">${escapeHtml(item.method)}</span>${escapeHtml(item.path)}</td><td class="col-status"><span class="status-badge ${statusClass(item.status)}">${escapeHtml(String(item.status))}</span></td><td class="col-fixture" title="${escapeHtml(fixture)}">${escapeHtml(fixture)}</td><td class="col-network" title="${escapeHtml(network)}">${escapeHtml(network)}</td><td class="col-bodies">${bodyLink("req", item.requestBodyFile)}${bodyLink("resp", item.responseBodyFile)}</td></tr>`;
    }).join("")
    : `<tr><td colspan="7" class="muted">Journal is empty</td></tr>`;
}

async function loadJson(path) {
  const response = await fetch(withCacheBuster(path), { cache: "no-store" });
  if (!response.ok) throw new Error(`${response.status} ${path}`);
  return response.json();
}

async function refresh() {
  if (refreshInFlight) return;
  refreshInFlight = true;
  refreshButton.disabled = true;
  refreshButton.textContent = "Refreshing...";
  markRefresh("", "updating");
  try {
    const [status, state, journal] = await Promise.all([
      loadJson(`${initialStatus.adminBasePath}/api/status`),
      loadJson(`${initialStatus.adminBasePath}/api/state`),
      loadJson(`${initialStatus.adminBasePath}/api/journal?limit=50`)
    ]);
    renderStatus(status);
    renderState(state);
    renderJournal(journal.items);
    markRefresh("ok", `updated ${formatTime(new Date().toISOString())}`);
  } catch (error) {
    markRefresh("error", "refresh failed");
    console.error("Admin refresh failed", error);
  } finally {
    refreshInFlight = false;
    refreshButton.disabled = false;
    refreshButton.textContent = "Refresh";
  }
}

function syncTimer() {
  if (timerId) {
    clearInterval(timerId);
    timerId = null;
  }
  if (autoRefresh.checked) {
    timerId = setInterval(refresh, 3000);
  }
}

refreshButton.addEventListener("click", refresh);
autoRefresh.addEventListener("change", syncTimer);
renderStatus(initialStatus);
syncTimer();
refresh();
