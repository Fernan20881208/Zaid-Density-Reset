import { createClient } from "npm:@supabase/supabase-js@2";
import {
  calculateTokenExpiry,
  generateLicenseKey,
  isValidLicenseKey,
  keyPrefix,
  keySuffix,
  maskedKey,
  normalizeLicenseKey,
  sha256Hex,
  signLicenseToken,
  verifyLicenseToken,
} from "./logic.ts";

const SUPABASE_URL = requiredEnv("SUPABASE_URL");
const SUPABASE_ANON_KEY = requiredEnv("SUPABASE_ANON_KEY");
const SUPABASE_SERVICE_ROLE_KEY = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
const LICENSE_SIGNING_SECRET = requiredEnv("LICENSE_SIGNING_SECRET");
const ALLOWED_PACKAGE_NAME = Deno.env.get("ALLOWED_PACKAGE_NAME") ?? "com.zaidnavarro.ds";

const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

type Settings = {
  minimum_app_version_code: number;
  offline_grace_hours: number;
  token_lifetime_hours: number;
};

type AdminIdentity = { userId: string };

Deno.serve(async (request) => {
  const requestId = crypto.randomUUID();
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }

  const route = normalizedPath(new URL(request.url).pathname);
  let response: Response;
  try {
    if (route === "/license/activate" && request.method === "POST") {
      response = await activateLicense(request);
    } else if (route === "/license/validate" && request.method === "POST") {
      response = await validateLicense(request);
    } else if (route.startsWith("/admin/")) {
      response = await handleAdmin(request, route);
    } else {
      response = json({ success: false, code: "NOT_FOUND" }, 404);
    }
  } catch {
    response = json({ success: false, code: "SERVER_ERROR" }, 500);
  }

  console.info(JSON.stringify({ requestId, method: request.method, route, status: response.status }));
  return response;
});

async function activateLicense(request: Request): Promise<Response> {
  const body = await readJson(request);
  const key = typeof body.key === "string" ? normalizeLicenseKey(body.key) : "";
  const deviceHash = typeof body.deviceHash === "string" ? body.deviceHash.toLowerCase() : "";
  const appVersion = Number(body.appVersion ?? 0);
  const packageName = String(body.packageName ?? "");

  if (!/^[0-9a-f]{64}$/.test(deviceHash)) return apiError("INVALID_KEY", 400);

  const settings = await getSettings();
  const appFailure = validateApp(packageName, appVersion, settings);
  if (appFailure) return appFailure;

  const ip = clientIp(request);
  const fingerprint = await sha256Hex(`${ip ?? "unknown"}|${deviceHash}`);
  const { data: allowed, error: rateError } = await service.rpc(
    "consume_license_rate_limit",
    { p_fingerprint: fingerprint, p_limit: 5 },
  );
  if (rateError) return apiError("SERVER_ERROR", 500);
  if (!allowed) return apiError("RATE_LIMITED", 429);

  if (!isValidLicenseKey(key)) return apiError("INVALID_KEY", 400);
  const keyHash = await sha256Hex(key);
  const { data, error } = await service.rpc("activate_license", {
    p_key_hash: keyHash,
    p_device_hash: deviceHash,
    p_ip: ip,
  });
  if (error || !data) return apiError("SERVER_ERROR", 500);
  if (!data.success) return apiError(data.code ?? "INVALID_KEY", statusForCode(data.code), data.expires_at);

  const token = await issueToken(
    String(data.license_id),
    deviceHash,
    data.expires_at ?? null,
    settings.token_lifetime_hours,
  );

  return json({
    success: true,
    status: "active",
    expiresAt: data.expires_at ?? null,
    licenseToken: token.value,
    tokenExpiresAt: token.expiresAt,
  });
}

async function validateLicense(request: Request): Promise<Response> {
  const body = await readJson(request);
  const deviceHash = typeof body.deviceHash === "string" ? body.deviceHash.toLowerCase() : "";
  const appVersion = Number(body.appVersion ?? 0);
  const packageName = String(body.packageName ?? "");
  if (!/^[0-9a-f]{64}$/.test(deviceHash)) return apiError("INVALID_SESSION", 401);

  const settings = await getSettings();
  const appFailure = validateApp(packageName, appVersion, settings);
  if (appFailure) return appFailure;

  const authorization = request.headers.get("authorization") ?? "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
  if (!token) return apiError("INVALID_SESSION", 401);

  const verified = await verifyLicenseToken(token, LICENSE_SIGNING_SECRET, deviceHash);
  if (!verified.ok) return apiError(verified.code, verified.code === "TOKEN_EXPIRED" ? 401 : 403);

  const { data, error } = await service.rpc("validate_license_session", {
    p_license_id: verified.payload.licenseId,
    p_device_hash: deviceHash,
  });
  if (error || !data) return apiError("SERVER_ERROR", 500);
  if (!data.success) return apiError(data.code ?? "INVALID_SESSION", statusForCode(data.code), data.expires_at);

  const refreshed = await issueToken(
    String(data.license_id),
    deviceHash,
    data.expires_at ?? null,
    settings.token_lifetime_hours,
  );
  return json({
    success: true,
    status: "active",
    expiresAt: data.expires_at ?? null,
    licenseToken: refreshed.value,
    tokenExpiresAt: refreshed.expiresAt,
  });
}

async function handleAdmin(request: Request, route: string): Promise<Response> {
  const admin = await requireAdmin(request);
  if (admin instanceof Response) return admin;

  if (route === "/admin/dashboard" && request.method === "GET") {
    return adminDashboard();
  }
  if (route === "/admin/licenses" && request.method === "GET") {
    return listLicenses(request);
  }
  if (route === "/admin/licenses/create" && request.method === "POST") {
    return createLicenses(request, admin, 1);
  }
  if (route === "/admin/licenses/create-bulk" && request.method === "POST") {
    return createLicenses(request, admin, null);
  }
  if (route === "/admin/licenses/revoke" && request.method === "POST") {
    return mutateStatus(request, "revoked");
  }
  if (route === "/admin/licenses/disable" && request.method === "POST") {
    return mutateStatus(request, "disabled");
  }
  if (route === "/admin/licenses/enable" && request.method === "POST") {
    return enableLicense(request);
  }
  if (route === "/admin/licenses/reset-device" && request.method === "POST") {
    return resetDevices(request);
  }
  if (route.startsWith("/admin/licenses/") && request.method === "GET") {
    return licenseDetails(route.split("/").at(-1) ?? "");
  }
  if (route.startsWith("/admin/licenses/") && request.method === "DELETE") {
    return deleteLicense(route.split("/").at(-1) ?? "");
  }
  return json({ success: false, code: "NOT_FOUND" }, 404);
}

async function requireAdmin(request: Request): Promise<AdminIdentity | Response> {
  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return json({ success: false, code: "UNAUTHORIZED" }, 401);
  const token = authorization.slice(7).trim();
  const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await userClient.auth.getUser(token);
  if (userError || !userData.user) return json({ success: false, code: "UNAUTHORIZED" }, 401);

  const { data: profile, error } = await service
    .from("profiles")
    .select("role")
    .eq("user_id", userData.user.id)
    .maybeSingle();
  if (error || profile?.role !== "admin") return json({ success: false, code: "FORBIDDEN" }, 403);
  return { userId: userData.user.id };
}

async function createLicenses(request: Request, admin: AdminIdentity, forcedQuantity: number | null): Promise<Response> {
  const body = await readJson(request);
  const quantity = forcedQuantity ?? Number(body.quantity ?? 1);
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) {
    return json({ success: false, code: "INVALID_QUANTITY" }, 400);
  }

  const durationDays = body.durationDays === null || body.durationDays === "permanent"
    ? null
    : Number(body.durationDays);
  if (durationDays !== null && (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 3650)) {
    return json({ success: false, code: "INVALID_DURATION" }, 400);
  }
  const startMode = body.durationStartMode === "generation" ? "generation" : "first_activation";
  const maxDevices = Number(body.maxDevices ?? 1);
  if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) {
    return json({ success: false, code: "INVALID_DEVICE_LIMIT" }, 400);
  }
  const label = cleanOptionalText(body.label, 120);
  const notes = cleanOptionalText(body.notes, 1000);
  const createdAt = new Date();
  const expiresAt = durationDays !== null && startMode === "generation"
    ? new Date(createdAt.getTime() + durationDays * 86_400_000).toISOString()
    : null;

  const generated: Array<{ key: string; row: Record<string, unknown> }> = [];
  const seen = new Set<string>();
  while (generated.length < quantity) {
    const key = generateLicenseKey();
    const hash = await sha256Hex(key);
    if (seen.has(hash)) continue;
    seen.add(hash);
    generated.push({
      key,
      row: {
        key_hash: hash,
        key_prefix: keyPrefix(key),
        key_suffix: keySuffix(key),
        status: "unused",
        expires_at: expiresAt,
        duration_days: durationDays,
        duration_start_mode: startMode,
        max_devices: maxDevices,
        label,
        notes,
        created_by: admin.userId,
      },
    });
  }

  const { data, error } = await service
    .from("licenses")
    .insert(generated.map((item) => item.row))
    .select("id,key_prefix,key_suffix,status,created_at,expires_at,duration_days,duration_start_mode,max_devices,label");
  if (error || !data || data.length !== generated.length) return apiError("SERVER_ERROR", 500);

  return json({
    success: true,
    licenses: data.map((row, index) => ({
      ...row,
      key: generated[index].key,
    })),
    warning: "Guarda estas keys ahora. Por seguridad no podrán volver a mostrarse completas.",
  }, 201);
}

async function listLicenses(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const search = (url.searchParams.get("search") ?? "").trim().toLowerCase();
  const statusFilter = (url.searchParams.get("status") ?? "all").toLowerCase();
  const offset = clampInt(url.searchParams.get("offset"), 0, 100000, 0);
  const limit = clampInt(url.searchParams.get("limit"), 1, 100, 50);

  const { data, error } = await service
    .from("licenses")
    .select("id,key_prefix,key_suffix,status,created_at,activated_at,expires_at,duration_days,duration_start_mode,max_devices,last_validation,label,notes,revoked_at,disabled_at,license_devices(device_hash,last_validation)")
    .order("created_at", { ascending: false })
    .limit(5000);
  if (error || !data) return apiError("SERVER_ERROR", 500);

  const normalized = data.map(publicAdminLicenseRow);
  const filtered = normalized.filter((row) => {
    if (statusFilter !== "all" && row.status !== statusFilter) return false;
    if (!search) return true;
    const haystack = [
      row.key,
      row.label ?? "",
      row.status,
      ...(row.devices as string[]),
    ].join(" ").toLowerCase();
    return haystack.includes(search);
  });

  return json({
    success: true,
    total: filtered.length,
    licenses: filtered.slice(offset, offset + limit),
  });
}

async function licenseDetails(id: string): Promise<Response> {
  if (!isUuid(id)) return json({ success: false, code: "NOT_FOUND" }, 404);
  const { data, error } = await service
    .from("licenses")
    .select("id,key_prefix,key_suffix,status,created_at,activated_at,expires_at,duration_days,duration_start_mode,max_devices,last_validation,label,notes,revoked_at,disabled_at,license_devices(device_hash,activated_at,last_validation)")
    .eq("id", id)
    .maybeSingle();
  if (error) return apiError("SERVER_ERROR", 500);
  if (!data) return json({ success: false, code: "NOT_FOUND" }, 404);
  return json({ success: true, license: publicAdminLicenseRow(data, true) });
}

async function mutateStatus(request: Request, nextStatus: "revoked" | "disabled"): Promise<Response> {
  const body = await readJson(request);
  const id = String(body.id ?? "");
  if (!isUuid(id)) return json({ success: false, code: "NOT_FOUND" }, 404);
  const now = new Date().toISOString();
  const patch = nextStatus === "revoked"
    ? { status: "revoked", revoked_at: now }
    : { status: "disabled", disabled_at: now };
  const { error } = await service.from("licenses").update(patch).eq("id", id);
  if (error) return apiError("SERVER_ERROR", 500);
  return json({ success: true, status: nextStatus });
}

async function enableLicense(request: Request): Promise<Response> {
  const body = await readJson(request);
  const id = String(body.id ?? "");
  if (!isUuid(id)) return json({ success: false, code: "NOT_FOUND" }, 404);
  const { data: license, error } = await service
    .from("licenses")
    .select("activated_at,expires_at")
    .eq("id", id)
    .maybeSingle();
  if (error) return apiError("SERVER_ERROR", 500);
  if (!license) return json({ success: false, code: "NOT_FOUND" }, 404);
  const expired = license.expires_at && new Date(license.expires_at).getTime() <= Date.now();
  const status = expired ? "expired" : (license.activated_at ? "active" : "unused");
  const { error: updateError } = await service.from("licenses").update({
    status,
    revoked_at: null,
    disabled_at: null,
  }).eq("id", id);
  if (updateError) return apiError("SERVER_ERROR", 500);
  return json({ success: true, status });
}

async function resetDevices(request: Request): Promise<Response> {
  const body = await readJson(request);
  const id = String(body.id ?? "");
  if (!isUuid(id)) return json({ success: false, code: "NOT_FOUND" }, 404);
  const { error: deviceError } = await service.from("license_devices").delete().eq("license_id", id);
  if (deviceError) return apiError("SERVER_ERROR", 500);
  const { error } = await service.from("licenses").update({ device_hash: null }).eq("id", id);
  if (error) return apiError("SERVER_ERROR", 500);
  return json({ success: true });
}

async function deleteLicense(id: string): Promise<Response> {
  if (!isUuid(id)) return json({ success: false, code: "NOT_FOUND" }, 404);
  const { error } = await service.from("licenses").delete().eq("id", id);
  if (error) return apiError("SERVER_ERROR", 500);
  return json({ success: true });
}

async function adminDashboard(): Promise<Response> {
  const { data, error } = await service.from("licenses").select("status,expires_at").limit(10000);
  if (error || !data) return apiError("SERVER_ERROR", 500);
  const counts = { active: 0, unused: 0, expired: 0, revoked: 0, disabled: 0 };
  data.forEach((row) => {
    const status = effectiveStatus(row);
    counts[status as keyof typeof counts] += 1;
  });
  return json({ success: true, counts });
}

async function issueToken(licenseId: string, deviceHash: string, expiresAt: string | null, lifetimeHours: number) {
  const issuedAt = Math.floor(Date.now() / 1000);
  const exp = calculateTokenExpiry(issuedAt, lifetimeHours, expiresAt);
  const value = await signLicenseToken({
    licenseId,
    deviceHash,
    issuedAt,
    exp,
    nonce: crypto.randomUUID(),
  }, LICENSE_SIGNING_SECRET);
  return { value, expiresAt: new Date(exp * 1000).toISOString() };
}

async function getSettings(): Promise<Settings> {
  const { data, error } = await service
    .from("license_settings")
    .select("minimum_app_version_code,offline_grace_hours,token_lifetime_hours")
    .eq("singleton", true)
    .single();
  if (error || !data) throw new Error("settings unavailable");
  return data as Settings;
}

function validateApp(packageName: string, appVersion: number, settings: Settings): Response | null {
  if (packageName !== ALLOWED_PACKAGE_NAME) return apiError("APP_VERSION_BLOCKED", 403);
  if (!Number.isInteger(appVersion) || appVersion < settings.minimum_app_version_code) {
    return apiError("APP_VERSION_BLOCKED", 426);
  }
  return null;
}

function publicAdminLicenseRow(row: any, detailed = false) {
  const devices = (row.license_devices ?? []).map((item: any) => {
    const partial = typeof item.device_hash === "string" ? `${item.device_hash.slice(0, 12)}…` : "";
    return detailed ? {
      hash: partial,
      activatedAt: item.activated_at ?? null,
      lastValidation: item.last_validation ?? null,
    } : partial;
  });
  return {
    id: row.id,
    key: maskedKey(row.key_prefix, row.key_suffix),
    status: effectiveStatus(row),
    durationDays: row.duration_days,
    durationStartMode: row.duration_start_mode,
    maxDevices: row.max_devices,
    createdAt: row.created_at,
    activatedAt: row.activated_at,
    expiresAt: row.expires_at,
    lastValidation: row.last_validation,
    label: row.label,
    ...(detailed ? { notes: row.notes, revokedAt: row.revoked_at, disabledAt: row.disabled_at } : {}),
    devices,
  };
}

function effectiveStatus(row: any): string {
  if (row.status === "revoked" || row.status === "disabled" || row.status === "unused") return row.status;
  if (row.expires_at && new Date(row.expires_at).getTime() <= Date.now()) return "expired";
  return row.status === "expired" ? "expired" : "active";
}

function normalizedPath(pathname: string): string {
  const marker = "/license-api";
  const index = pathname.indexOf(marker);
  if (index >= 0) return pathname.slice(index + marker.length) || "/";
  return pathname;
}

async function readJson(request: Request): Promise<Record<string, any>> {
  const length = Number(request.headers.get("content-length") ?? 0);
  if (length > 64_000) throw new Error("payload too large");
  return await request.json();
}

function clientIp(request: Request): string | null {
  const value = request.headers.get("cf-connecting-ip") ?? request.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (!value || value.length > 64) return null;
  return value;
}

function cleanOptionalText(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const cleaned = value.trim().slice(0, maxLength);
  return cleaned || null;
}

function clampInt(raw: string | null, min: number, max: number, fallback: number): number {
  const value = Number(raw);
  if (!Number.isInteger(value)) return fallback;
  return Math.min(max, Math.max(min, value));
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function statusForCode(code: string | undefined): number {
  switch (code) {
    case "LICENSE_REVOKED":
    case "LICENSE_DISABLED":
    case "DEVICE_LIMIT":
    case "DEVICE_MISMATCH":
      return 403;
    case "LICENSE_EXPIRED":
      return 410;
    case "INVALID_SESSION":
    case "TOKEN_EXPIRED":
      return 401;
    default:
      return 400;
  }
}

function apiError(code: string, status: number, expiresAt?: string | null): Response {
  return json({ success: false, code, ...(expiresAt ? { expiresAt } : {}) }, status);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}
