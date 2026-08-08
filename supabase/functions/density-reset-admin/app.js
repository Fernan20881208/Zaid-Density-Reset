import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const { config } = await import("./config.js");
const supabase = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
  auth: { persistSession: true, autoRefreshToken: true },
});

const $ = (id) => document.getElementById(id);
const loginView = $("loginView");
const adminView = $("adminView");
const loginForm = $("loginForm");
const loginError = $("loginError");
const generatorForm = $("generatorForm");
const generationResult = $("generationResult");
const generatedKeysView = $("generatedKeys");
const licensesBody = $("licensesBody");
const detailsDialog = $("detailsDialog");
let generatedLicenses = [];
let searchTimer = null;

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  hideMessage(loginError);
  const email = $("email").value.trim();
  const password = $("password").value;
  const { error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) showMessage(loginError, "Correo o contraseña incorrectos, o usuario sin acceso.");
});

$("logoutAdmin").addEventListener("click", () => supabase.auth.signOut());

$("duration").addEventListener("change", () => {
  $("customDurationField").hidden = $("duration").value !== "custom";
});

generatorForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const quantity = Number($("quantity").value);
  const selectedDuration = $("duration").value;
  const durationDays = selectedDuration === "permanent"
    ? null
    : Number(selectedDuration === "custom" ? $("customDuration").value : selectedDuration);
  const payload = {
    quantity,
    durationDays,
    durationStartMode: $("durationStartMode").value,
    maxDevices: Number($("maxDevices").value),
    label: $("label").value.trim(),
    notes: $("notes").value.trim(),
  };
  const button = $("generateButton");
  button.disabled = true;
  button.textContent = quantity === 1 ? "Generando…" : `Generando ${quantity}…`;
  try {
    const route = quantity === 1 ? "/admin/licenses/create" : "/admin/licenses/create-bulk";
    const result = await api(route, { method: "POST", body: payload });
    generatedLicenses = result.licenses ?? [];
    generatedKeysView.textContent = generatedLicenses.map((license) => license.key).join("\n");
    generationResult.hidden = false;
    await loadAll();
  } catch (error) {
    alert(error.message);
  } finally {
    button.disabled = false;
    button.textContent = quantity === 1 ? "Generar" : `Generar ${quantity} keys`;
  }
});

$("copyAll").addEventListener("click", async () => {
  const text = generatedLicenses.map((item) => item.key).join("\n");
  if (text) await navigator.clipboard.writeText(text);
});

$("exportCsv").addEventListener("click", () => {
  if (!generatedLicenses.length) return;
  const rows = [
    ["key", "duration_days", "duration_start_mode", "max_devices", "label"],
    ...generatedLicenses.map((item) => [
      item.key,
      item.duration_days ?? "permanent",
      item.duration_start_mode,
      item.max_devices,
      item.label ?? "",
    ]),
  ];
  const csv = rows.map((row) => row.map(csvCell).join(",")).join("\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `density-reset-keys-${new Date().toISOString().slice(0, 10)}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
});

$("search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadLicenses, 250);
});
$("statusFilter").addEventListener("change", loadLicenses);

licensesBody.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = button.dataset.id;
  const action = button.dataset.action;
  if (action === "details") return showDetails(id);

  const confirmations = {
    revoke: "¿Revocar esta licencia? El dispositivo perderá acceso en la próxima validación.",
    disable: "¿Deshabilitar temporalmente esta licencia?",
    enable: "¿Reactivar esta licencia? Se respetará su fecha de expiración original.",
    "reset-device": "¿Resetear los dispositivos vinculados? La licencia podrá activarse en otro dispositivo.",
    delete: "¿Eliminar esta licencia permanentemente? Esta acción no se puede deshacer.",
  };
  if (!confirm(confirmations[action] ?? "¿Confirmar esta acción?")) return;

  try {
    if (action === "delete") {
      await api(`/admin/licenses/${id}`, { method: "DELETE" });
    } else {
      await api(`/admin/licenses/${action}`, { method: "POST", body: { id } });
    }
    await loadAll();
  } catch (error) {
    alert(error.message);
  }
});

supabase.auth.onAuthStateChange(async (_event, session) => {
  if (session) {
    loginView.hidden = true;
    adminView.hidden = false;
    $("adminIdentity").textContent = session.user.email ?? "Administrador";
    try {
      await loadAll();
    } catch (error) {
      showMessage($("tableError"), error.message);
    }
  } else {
    adminView.hidden = true;
    loginView.hidden = false;
    generatedLicenses = [];
    generationResult.hidden = true;
  }
});

const { data: initial } = await supabase.auth.getSession();
if (initial.session) {
  loginView.hidden = true;
  adminView.hidden = false;
  $("adminIdentity").textContent = initial.session.user.email ?? "Administrador";
  await loadAll().catch((error) => showMessage($("tableError"), error.message));
}

async function loadAll() {
  await Promise.all([loadDashboard(), loadLicenses()]);
}

async function loadDashboard() {
  const result = await api("/admin/dashboard");
  $("countActive").textContent = result.counts.active;
  $("countUnused").textContent = result.counts.unused;
  $("countExpired").textContent = result.counts.expired;
  $("countRevoked").textContent = result.counts.revoked;
  $("countDisabled").textContent = result.counts.disabled;
}

async function loadLicenses() {
  hideMessage($("tableError"));
  const params = new URLSearchParams({
    search: $("search").value.trim(),
    status: $("statusFilter").value,
    limit: "100",
    offset: "0",
  });
  const result = await api(`/admin/licenses?${params}`);
  $("licenseTotal").textContent = `${result.total} resultados`;
  licensesBody.replaceChildren(...result.licenses.map(renderLicenseRow));
}

function renderLicenseRow(license) {
  const row = document.createElement("tr");
  const duration = license.durationDays == null
    ? "Permanente"
    : `${license.durationDays} días · ${license.durationStartMode === "generation" ? "desde generación" : "desde activación"}`;
  row.innerHTML = `
    <td><code>${escapeHtml(license.key)}</code></td>
    <td><span class="badge ${escapeHtml(license.status)}">${statusLabel(license.status)}</span></td>
    <td>${escapeHtml(duration)}</td>
    <td>${formatDate(license.createdAt)}</td>
    <td>${formatDate(license.activatedAt)}</td>
    <td>${license.expiresAt ? formatDate(license.expiresAt) : "Permanente"}</td>
    <td>${escapeHtml((license.devices ?? []).join(", ") || "Sin vincular")}</td>
    <td>${formatDate(license.lastValidation)}</td>
    <td><div class="row-actions">
      <button data-action="details" data-id="${license.id}">Detalles</button>
      <button class="secondary" data-action="disable" data-id="${license.id}">Deshabilitar</button>
      <button class="secondary" data-action="enable" data-id="${license.id}">Reactivar</button>
      <button class="secondary" data-action="reset-device" data-id="${license.id}">Reset dispositivo</button>
      <button class="danger" data-action="revoke" data-id="${license.id}">Revocar</button>
      <button class="danger" data-action="delete" data-id="${license.id}">Eliminar</button>
    </div></td>`;
  return row;
}

async function showDetails(id) {
  try {
    const result = await api(`/admin/licenses/${id}`);
    const license = result.license;
    const devices = (license.devices ?? []).map((item) => item.hash).join(", ") || "Sin vincular";
    $("detailsContent").innerHTML = `<dl class="detail-grid">
      <dt>Key</dt><dd>${escapeHtml(license.key)}</dd>
      <dt>Estado</dt><dd>${escapeHtml(statusLabel(license.status))}</dd>
      <dt>Etiqueta</dt><dd>${escapeHtml(license.label ?? "—")}</dd>
      <dt>Notas</dt><dd>${escapeHtml(license.notes ?? "—")}</dd>
      <dt>Creada</dt><dd>${formatDate(license.createdAt)}</dd>
      <dt>Activada</dt><dd>${formatDate(license.activatedAt)}</dd>
      <dt>Expira</dt><dd>${license.expiresAt ? formatDate(license.expiresAt) : "Permanente"}</dd>
      <dt>Dispositivos</dt><dd>${escapeHtml(devices)}</dd>
      <dt>Última validación</dt><dd>${formatDate(license.lastValidation)}</dd>
    </dl>`;
    detailsDialog.showModal();
  } catch (error) {
    alert(error.message);
  }
}

async function api(path, { method = "GET", body } = {}) {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new Error("La sesión del administrador expiró.");
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
    if (response.status === 401) await supabase.auth.signOut();
    throw new Error(adminErrorMessage(result.code));
  }
  return result;
}

function adminErrorMessage(code) {
  const messages = {
    UNAUTHORIZED: "La sesión del administrador no es válida.",
    FORBIDDEN: "Esta cuenta no tiene el rol admin.",
    INVALID_QUANTITY: "La cantidad debe estar entre 1 y 100.",
    INVALID_DURATION: "La duración personalizada no es válida.",
    INVALID_DEVICE_LIMIT: "El límite de dispositivos no es válido.",
    SERVER_ERROR: "El servidor no pudo completar la operación.",
    NOT_FOUND: "No se encontró la licencia.",
  };
  return messages[code] ?? "No fue posible completar la operación.";
}

function statusLabel(status) {
  return ({ active: "Activa", unused: "Sin activar", expired: "Expirada", revoked: "Revocada", disabled: "Deshabilitada" })[status] ?? status;
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function csvCell(value) {
  return `"${String(value ?? "").replaceAll('"', '""')}"`;
}

function showMessage(element, message) {
  element.textContent = message;
  element.hidden = false;
}
function hideMessage(element) { element.hidden = true; }
function escapeHtml(value) {
  const span = document.createElement("span");
  span.textContent = String(value ?? "");
  return span.innerHTML;
}
