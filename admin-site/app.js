import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { config } from "./config.js";

const supabase = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
});

const $ = (id) => document.getElementById(id);
const loginUrl = new URL("../", window.location.href).toString();
let licenses = [];
let activeLicenseId = null;
let searchTimer = null;

await boot();

$("logout").addEventListener("click", async () => {
  await supabase.auth.signOut();
  window.location.replace(loginUrl);
});

$("refresh").addEventListener("click", async () => {
  try { await loadAll(); toast("Datos actualizados"); }
  catch (error) { handleError(error); }
});

$("newKey").addEventListener("click", () => $("generatorPanel").scrollIntoView({ behavior: "smooth", block: "start" }));
$("search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadLicenses().catch(handleError), 250);
});
$("filter").addEventListener("change", () => loadLicenses().catch(handleError));
$("close").addEventListener("click", () => $("modal").close());

$("genForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  hideGeneratorError();
  const quantity = Math.max(1, Math.min(100, Number($("qty").value || 1)));
  const selectedDuration = $("duration").value;
  const durationDays = selectedDuration === "permanent" ? null : Number(selectedDuration);
  const payload = {
    quantity,
    durationDays,
    durationStartMode: $("start").value,
    maxDevices: Math.max(1, Math.min(100, Number($("maxDevices").value || 1))),
    label: $("label").value.trim(),
    notes: "",
  };

  const button = $("generateButton");
  setBusy(button, true, quantity === 1 ? "Generando…" : `Generando ${quantity}…`);
  try {
    const route = quantity === 1 ? "/admin/licenses/create" : "/admin/licenses/create-bulk";
    const result = await api(route, { method: "POST", body: payload });
    const created = Array.isArray(result.licenses) ? result.licenses : [];
    if (!created.length) throw new Error("La API respondió sin keys generadas.");
    $("keyOutput").textContent = created.map((item) => item.key).filter(Boolean).join("\n");
    $("generated").hidden = false;
    toast(quantity === 1 ? "Key generada correctamente" : `${created.length} keys generadas`);
    await loadAll();
  } catch (error) {
    showGeneratorError(readableError(error));
    handleError(error, false);
  } finally {
    setBusy(button, false, "Generar key");
  }
});

$("copy").addEventListener("click", async () => {
  const text = $("keyOutput").textContent.trim();
  if (!text) return;
  try { await navigator.clipboard.writeText(text); toast("Keys copiadas"); }
  catch { toast("Mantén presionado el texto para copiarlo"); }
});

$("cards").addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = button.dataset.id;
  const action = button.dataset.action;
  if (action === "details") return openDetails(id);
  if (action === "toggle") {
    const current = licenses.find((item) => item.id === id);
    if (!current) return;
    const next = current.status === "disabled" ? "enable" : "disable";
    await performAction(id, next);
  }
});

$("modalActions").addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button || !activeLicenseId) return;
  const action = button.dataset.action;
  if (action === "delete" && !confirm("¿Eliminar esta licencia permanentemente?")) return;
  if (action === "revoke" && !confirm("¿Revocar esta licencia?")) return;
  if (action === "reset-device" && !confirm("¿Resetear los dispositivos vinculados?")) return;
  await performAction(activeLicenseId, action, true);
});

supabase.auth.onAuthStateChange((event, session) => {
  if (event === "SIGNED_OUT" || !session) window.location.replace(loginUrl);
});

async function boot() {
  const { data } = await supabase.auth.getSession();
  const session = data.session;
  if (!session) {
    window.location.replace(loginUrl);
    return;
  }
  $("adminIdentity").textContent = session.user.email ?? "Administrador";
  try {
    await loadAll();
    $("boot").hidden = true;
    $("dash").hidden = false;
  } catch (error) {
    if (error?.status === 403) {
      await supabase.auth.signOut();
      window.location.replace(`${loginUrl}?error=forbidden`);
      return;
    }
    $("boot").querySelector("h2").textContent = "No se pudo cargar el panel";
    $("boot").querySelector(".lead").textContent = readableError(error);
  }
}

async function loadAll() {
  document.body.classList.add("loading");
  try {
    await Promise.all([loadDashboard(), loadLicenses()]);
    renderActivity();
  } finally {
    document.body.classList.remove("loading");
  }
}

async function loadDashboard() {
  const result = await api("/admin/dashboard");
  $("cActive").textContent = result.counts?.active ?? 0;
  $("cUnused").textContent = result.counts?.unused ?? 0;
  $("cExpired").textContent = result.counts?.expired ?? 0;
  $("cBlocked").textContent = (result.counts?.disabled ?? 0) + (result.counts?.revoked ?? 0);
}

async function loadLicenses() {
  const params = new URLSearchParams({
    search: $("search").value.trim(),
    status: $("filter").value,
    limit: "100",
    offset: "0",
  });
  const result = await api(`/admin/licenses?${params}`);
  licenses = result.licenses ?? [];
  renderRows();
  renderActivity();
}

function renderRows() {
  $("empty").hidden = licenses.length !== 0;
  $("cards").innerHTML = licenses.map((item) => `
    <article class="row">
      <div><span class="ml">Key</span><div class="key">${esc(item.key)}</div><div class="mv">${esc(item.label || "Sin etiqueta")}</div></div>
      <div><span class="ml">Estado</span><span class="badge ${esc(item.status)}">${statusLabel(item.status)}</span></div>
      <div class="mobile-hide"><span class="ml">Duración</span><div class="mv">${esc(durationLabel(item))}</div></div>
      <div class="mobile-hide tablet-hide"><span class="ml">Dispositivo</span><div class="mv">${esc((item.devices ?? []).join(", ") || "Sin vincular")}</div></div>
      <div class="actions"><button data-action="details" data-id="${item.id}">Ver</button>${!["expired","revoked"].includes(item.status) ? `<button data-action="toggle" data-id="${item.id}">${item.status === "disabled" ? "Activar" : "Pausar"}</button>` : ""}</div>
    </article>`).join("");
}

function renderActivity() {
  const latest = licenses.slice(0, 4);
  if (!latest.length) {
    $("activity").innerHTML = `<div class="activity-item"><span class="dot"></span><div><strong>Sin movimientos todavía</strong><span>Genera tu primera key.</span></div></div>`;
    return;
  }
  $("activity").innerHTML = latest.map((item) => {
    const title = item.status === "active" ? "Licencia activa" : item.status === "unused" ? "Nueva licencia" : item.status === "disabled" ? "Licencia pausada" : item.status === "revoked" ? "Licencia revocada" : "Licencia expirada";
    return `<div class="activity-item"><span class="dot"></span><div><strong>${title}</strong><span>${esc(item.key)} · ${relativeTime(item.createdAt)}</span></div></div>`;
  }).join("");
}

async function openDetails(id) {
  try {
    const result = await api(`/admin/licenses/${id}`);
    const item = result.license;
    activeLicenseId = item.id;
    $("modalKey").textContent = item.key;
    const deviceText = (item.devices ?? []).map((device) => device.hash).join(", ") || "Sin vincular";
    const rows = [
      ["Estado", statusLabel(item.status)],
      ["Etiqueta", item.label || "Sin etiqueta"],
      ["Duración", durationLabel(item)],
      ["Dispositivo", deviceText],
      ["Creada", formatDate(item.createdAt)],
      ["Activada", formatDate(item.activatedAt)],
      ["Expira", item.expiresAt ? formatDate(item.expiresAt) : "Permanente"],
      ["Última validación", formatDate(item.lastValidation)],
      ["Notas", item.notes || "—"],
    ];
    $("details").innerHTML = rows.map(([name, value]) => `<div class="detail"><span>${esc(name)}</span><span>${esc(value)}</span></div>`).join("");
    const actions = [];
    if (item.status === "disabled") actions.push(`<button class="ghost" data-action="enable">Reactivar</button>`);
    else if (!["expired", "revoked"].includes(item.status)) actions.push(`<button class="ghost" data-action="disable">Pausar</button>`);
    if (!["revoked", "expired"].includes(item.status)) actions.push(`<button class="ghost" data-action="reset-device">Reset dispositivo</button>`);
    if (item.status !== "revoked") actions.push(`<button class="danger" data-action="revoke">Revocar</button>`);
    actions.push(`<button class="danger" data-action="delete">Eliminar</button>`);
    $("modalActions").innerHTML = actions.join("");
    $("modal").showModal();
  } catch (error) { handleError(error); }
}

async function performAction(id, action, fromModal = false) {
  try {
    if (action === "delete") await api(`/admin/licenses/${id}`, { method: "DELETE" });
    else await api(`/admin/licenses/${action}`, { method: "POST", body: { id } });
    if (fromModal) $("modal").close();
    toast(actionMessage(action));
    await loadAll();
  } catch (error) { handleError(error); }
}

async function api(path, { method = "GET", body } = {}) {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) {
    const error = new Error("La sesión expiró.");
    error.status = 401;
    throw error;
  }
  const response = await fetch(`${config.LICENSE_API_URL.replace(/\/$/, "")}${path}`, {
    method,
    headers: {
      "Authorization": `Bearer ${token}`,
      "Content-Type": "application/json",
      "Accept": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({ success: false, code: "SERVER_ERROR" }));
  if (!response.ok || !result.success) {
    const error = new Error(errorMessage(result.code));
    error.code = result.code;
    error.status = response.status;
    throw error;
  }
  return result;
}

function handleError(error, showToast = true) {
  if (error?.status === 401) {
    supabase.auth.signOut().finally(() => window.location.replace(loginUrl));
    return;
  }
  if (showToast) toast(readableError(error));
}
function readableError(error) { return error?.message || "No fue posible completar la operación."; }
function errorMessage(code) { return ({ UNAUTHORIZED:"La sesión del administrador no es válida.", FORBIDDEN:"Esta cuenta no tiene permisos de administrador.", INVALID_QUANTITY:"La cantidad debe estar entre 1 y 100.", INVALID_DURATION:"La duración no es válida.", INVALID_DEVICE_LIMIT:"El límite de dispositivos no es válido.", NOT_FOUND:"No se encontró la licencia.", SERVER_ERROR:"Supabase no pudo completar la operación." })[code] ?? `Error de Supabase${code ? `: ${code}` : ""}.`; }
function durationLabel(item) { return item.durationDays == null ? "Permanente" : `${item.durationDays} días · ${item.durationStartMode === "generation" ? "desde generación" : "desde activación"}`; }
function statusLabel(status) { return ({ active:"Activa", unused:"Sin activar", expired:"Expirada", disabled:"Pausada", revoked:"Revocada" })[status] ?? status; }
function actionMessage(action) { return ({ enable:"Licencia reactivada", disable:"Licencia pausada", "reset-device":"Dispositivo reseteado", revoke:"Licencia revocada", delete:"Licencia eliminada" })[action] ?? "Acción completada"; }
function formatDate(value) { if (!value) return "—"; const date = new Date(value); return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("es-MX", { dateStyle:"medium", timeStyle:"short" }).format(date); }
function relativeTime(value) { if (!value) return "recientemente"; const ms = Date.now() - new Date(value).getTime(); if (!Number.isFinite(ms) || ms < 0) return "recientemente"; const min = Math.floor(ms/60000); if (min < 1) return "ahora"; if (min < 60) return `hace ${min} min`; const h = Math.floor(min/60); if (h < 24) return `hace ${h} h`; return `hace ${Math.floor(h/24)} d`; }
function toast(message) { document.querySelector(".toast")?.remove(); const element = document.createElement("div"); element.className = "toast"; element.textContent = message; document.body.appendChild(element); setTimeout(() => element.remove(), 2200); }
function esc(value) { const element = document.createElement("span"); element.textContent = String(value ?? ""); return element.innerHTML; }
function setBusy(button, busy, label) { button.disabled = busy; button.textContent = label; }
function showGeneratorError(message) { $("generatorError").textContent = message; $("generatorError").hidden = false; }
function hideGeneratorError() { $("generatorError").hidden = true; }
