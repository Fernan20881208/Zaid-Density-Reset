import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { config } from "./config.js";

const supabase = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
});

const $ = (id) => document.getElementById(id);
let licenses = [];
let activeLicenseId = null;
let searchTimer = null;

$("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  hideLoginError();
  const button = $("loginButton");
  setBusy(button, true, "Entrando…");
  const { error } = await supabase.auth.signInWithPassword({
    email: $("email").value.trim(),
    password: $("password").value,
  });
  if (error) showLoginError("Correo o contraseña incorrectos, o usuario sin acceso.");
  setBusy(button, false, "Entrar");
});

$("githubLogin").addEventListener("click", async () => {
  hideLoginError();
  const button = $("githubLogin");
  setBusy(button, true, "Abriendo GitHub…");
  const redirectTo = `${window.location.origin}${window.location.pathname}`;
  const { error } = await supabase.auth.signInWithOAuth({
    provider: "github",
    options: { redirectTo, scopes: "read:user user:email" },
  });
  if (error) {
    showLoginError("No fue posible iniciar sesión con GitHub.");
    setBusy(button, false, "Entrar con GitHub");
  }
});

$("logout").addEventListener("click", () => supabase.auth.signOut());
$("refresh").addEventListener("click", async () => {
  try { await loadAll(); toast("Datos actualizados"); }
  catch (error) { toast(error.message); }
});
$("newKey").addEventListener("click", () => $("generatorPanel").scrollIntoView({ behavior: "smooth", block: "start" }));
$("search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadLicenses, 250);
});
$("filter").addEventListener("change", loadLicenses);
$("close").addEventListener("click", () => $("modal").close());

$("genForm").addEventListener("submit", async (event) => {
  event.preventDefault();
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
    const created = result.licenses ?? [];
    $("keyOutput").textContent = created.map((item) => item.key).join("\n");
    $("generated").hidden = false;
    toast(quantity === 1 ? "Key generada" : `${quantity} keys generadas`);
    await loadAll();
  } catch (error) {
    toast(error.message);
  } finally {
    setBusy(button, false, "Generar key");
  }
});

$("copy").addEventListener("click", async () => {
  const text = $("keyOutput").textContent.trim();
  if (!text) return;
  try { await navigator.clipboard.writeText(text); toast("Keys copiadas"); }
  catch { toast("Mantén presionado para copiar"); }
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
    const nextAction = current.status === "disabled" ? "enable" : "disable";
    await performAction(id, nextAction);
  }
});

$("modalActions").addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button || !activeLicenseId) return;
  const action = button.dataset.action;
  if (action === "delete") {
    if (!confirm("¿Eliminar esta licencia permanentemente? Esta acción no se puede deshacer.")) return;
  } else if (action === "revoke") {
    if (!confirm("¿Revocar esta licencia? El dispositivo perderá acceso en la próxima validación.")) return;
  } else if (action === "reset-device") {
    if (!confirm("¿Resetear los dispositivos vinculados? La key podrá usarse en otro dispositivo.")) return;
  }
  await performAction(activeLicenseId, action, true);
});

supabase.auth.onAuthStateChange(async (_event, session) => {
  if (session) await enterDashboard(session).catch(handleSessionError);
  else showLogin();
});

const { data: initial } = await supabase.auth.getSession();
if (initial.session) await enterDashboard(initial.session).catch(handleSessionError);

async function enterDashboard(session) {
  $("login").hidden = true;
  $("dash").hidden = false;
  $("adminIdentity").textContent = session.user.email ?? "Administrador";
  await loadAll();
}

function showLogin() {
  $("dash").hidden = true;
  $("login").hidden = false;
  licenses = [];
  activeLicenseId = null;
  $("generated").hidden = true;
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
  $("cActive").textContent = result.counts.active ?? 0;
  $("cUnused").textContent = result.counts.unused ?? 0;
  $("cExpired").textContent = result.counts.expired ?? 0;
  $("cBlocked").textContent = (result.counts.disabled ?? 0) + (result.counts.revoked ?? 0);
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
    const detailRows = [
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
    $("details").innerHTML = detailRows.map(([name, value]) => `<div class="detail"><span>${esc(name)}</span><span>${esc(value)}</span></div>`).join("");
    const actions = [];
    if (item.status === "disabled") actions.push(`<button class="ghost" data-action="enable">Reactivar</button>`);
    else if (!["expired", "revoked"].includes(item.status)) actions.push(`<button class="ghost" data-action="disable">Pausar</button>`);
    if (!["revoked", "expired"].includes(item.status)) actions.push(`<button class="ghost" data-action="reset-device">Reset dispositivo</button>`);
    if (item.status !== "revoked") actions.push(`<button class="danger" data-action="revoke">Revocar</button>`);
    actions.push(`<button class="danger" data-action="delete">Eliminar</button>`);
    $("modalActions").innerHTML = actions.join("");
    $("modal").showModal();
  } catch (error) { toast(error.message); }
}

async function performAction(id, action, fromModal = false) {
  try {
    if (action === "delete") await api(`/admin/licenses/${id}`, { method: "DELETE" });
    else await api(`/admin/licenses/${action}`, { method: "POST", body: { id } });
    if (fromModal) $("modal").close();
    toast(actionMessage(action));
    await loadAll();
  } catch (error) { toast(error.message); }
}

async function api(path, { method = "GET", body } = {}) {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new Error("La sesión del administrador expiró.");
  const response = await fetch(`${config.LICENSE_API_URL.replace(/\/$/, "")}${path}`, {
    method,
    headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "Accept": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({ success: false, code: "SERVER_ERROR" }));
  if (!response.ok || !result.success) {
    if (response.status === 401) await supabase.auth.signOut();
    throw new Error(errorMessage(result.code));
  }
  return result;
}

function durationLabel(item) {
  if (item.durationDays == null) return "Permanente";
  return `${item.durationDays} días · ${item.durationStartMode === "generation" ? "desde generación" : "desde activación"}`;
}
function statusLabel(status) { return ({ active:"Activa", unused:"Sin activar", expired:"Expirada", disabled:"Pausada", revoked:"Revocada" })[status] ?? status; }
function actionMessage(action) { return ({ enable:"Licencia reactivada", disable:"Licencia pausada", "reset-device":"Dispositivo reseteado", revoke:"Licencia revocada", delete:"Licencia eliminada" })[action] ?? "Acción completada"; }
function errorMessage(code) { return ({ UNAUTHORIZED:"La sesión del administrador no es válida.", FORBIDDEN:"Esta cuenta no tiene permisos de administrador.", INVALID_QUANTITY:"La cantidad debe estar entre 1 y 100.", INVALID_DURATION:"La duración no es válida.", INVALID_DEVICE_LIMIT:"El límite de dispositivos no es válido.", NOT_FOUND:"No se encontró la licencia.", SERVER_ERROR:"El servidor no pudo completar la operación." })[code] ?? "No fue posible completar la operación."; }
function formatDate(value) { if (!value) return "—"; const date = new Date(value); return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("es-MX", { dateStyle:"medium", timeStyle:"short" }).format(date); }
function relativeTime(value) { if (!value) return "recientemente"; const ms = Date.now() - new Date(value).getTime(); if (!Number.isFinite(ms) || ms < 0) return "recientemente"; const min = Math.floor(ms/60000); if (min < 1) return "ahora"; if (min < 60) return `hace ${min} min`; const h = Math.floor(min/60); if (h < 24) return `hace ${h} h`; const d = Math.floor(h/24); return `hace ${d} d`; }
function toast(message) { document.querySelector(".toast")?.remove(); const element = document.createElement("div"); element.className = "toast"; element.textContent = message; document.body.appendChild(element); setTimeout(() => element.remove(), 1900); }
function esc(value) { const element = document.createElement("span"); element.textContent = String(value ?? ""); return element.innerHTML; }
function setBusy(button, busy, label) { button.disabled = busy; button.textContent = label; }
function showLoginError(message) { $("loginError").textContent = message; $("loginError").hidden = false; }
function hideLoginError() { $("loginError").hidden = true; }
async function handleSessionError(error) { showLoginError(error.message); await supabase.auth.signOut(); }
