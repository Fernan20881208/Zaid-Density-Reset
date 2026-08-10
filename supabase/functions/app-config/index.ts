import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = requiredEnv("SUPABASE_URL");
const SUPABASE_ANON_KEY = requiredEnv("SUPABASE_ANON_KEY");
const SUPABASE_SERVICE_ROLE_KEY = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");

const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }

  try {
    if (request.method === "GET") {
      const config = await readConfig();
      return json({ success: true, config });
    }

    if (request.method === "PUT") {
      const admin = await requireAdmin(request);
      if (admin instanceof Response) return admin;
      const patch = sanitizePatch(await readJson(request));
      if (Object.keys(patch).length === 0) {
        return json({ success: false, code: "EMPTY_CONFIG" }, 400);
      }
      const { error } = await service
        .from("app_config")
        .update(patch)
        .eq("singleton", true);
      if (error) throw error;
      const config = await readConfig();
      return json({ success: true, config });
    }

    return json({ success: false, code: "METHOD_NOT_ALLOWED" }, 405);
  } catch (error) {
    console.error("app-config", error instanceof Error ? error.message : "unknown");
    return json({ success: false, code: "SERVER_ERROR" }, 500);
  }
});

async function readConfig() {
  const { data, error } = await service
    .from("app_config")
    .select("maintenance_mode,maintenance_message,min_supported_version_code,latest_version_code,force_update,free_fire_enabled,free_fire_max_enabled,sensi_ultra_enabled,sensi_high_enabled,sensi_low_enabled,sensi_ultra_density,sensi_high_density,sensi_low_density,game_session_duration_seconds,announcement_enabled,announcement_title,announcement_message,quick_tile_enabled,github_updates_enabled,blocked_version_codes,updated_at")
    .eq("singleton", true)
    .single();
  if (error || !data) throw error ?? new Error("config missing");
  return data;
}

async function requireAdmin(request: Request): Promise<{ userId: string } | Response> {
  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) {
    return json({ success: false, code: "UNAUTHORIZED" }, 401);
  }
  const token = authorization.slice(7).trim();
  const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await userClient.auth.getUser(token);
  if (userError || !userData.user) {
    return json({ success: false, code: "UNAUTHORIZED" }, 401);
  }

  const { data: profile, error } = await service
    .from("profiles")
    .select("role")
    .eq("user_id", userData.user.id)
    .maybeSingle();
  if (error || profile?.role !== "admin") {
    return json({ success: false, code: "FORBIDDEN" }, 403);
  }
  return { userId: userData.user.id };
}

function sanitizePatch(input: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};

  copyBoolean(input, result, "maintenance_mode");
  copyText(input, result, "maintenance_message", 1000, true);
  copyInteger(input, result, "min_supported_version_code", 1, Number.MAX_SAFE_INTEGER);
  copyNullableInteger(input, result, "latest_version_code", 1, Number.MAX_SAFE_INTEGER);
  copyBoolean(input, result, "force_update");
  copyBoolean(input, result, "free_fire_enabled");
  copyBoolean(input, result, "free_fire_max_enabled");
  copyBoolean(input, result, "sensi_ultra_enabled");
  copyBoolean(input, result, "sensi_high_enabled");
  copyBoolean(input, result, "sensi_low_enabled");
  copyInteger(input, result, "sensi_ultra_density", 20, 1000);
  copyInteger(input, result, "sensi_high_density", 20, 1000);
  copyInteger(input, result, "sensi_low_density", 20, 1000);
  copyInteger(input, result, "game_session_duration_seconds", 5, 300);
  copyBoolean(input, result, "announcement_enabled");
  copyText(input, result, "announcement_title", 120, true);
  copyText(input, result, "announcement_message", 2000, true);
  copyBoolean(input, result, "quick_tile_enabled");
  copyBoolean(input, result, "github_updates_enabled");

  if (Object.hasOwn(input, "blocked_version_codes")) {
    if (!Array.isArray(input.blocked_version_codes)) throw new Error("invalid blocked versions");
    const blocked = input.blocked_version_codes
      .map(Number)
      .filter((value) => Number.isSafeInteger(value) && value > 0)
      .slice(0, 128);
    result.blocked_version_codes = [...new Set(blocked)];
  }

  return result;
}

function copyBoolean(source: Record<string, unknown>, target: Record<string, unknown>, key: string) {
  if (!Object.hasOwn(source, key)) return;
  if (typeof source[key] !== "boolean") throw new Error(`invalid ${key}`);
  target[key] = source[key];
}

function copyInteger(
  source: Record<string, unknown>,
  target: Record<string, unknown>,
  key: string,
  min: number,
  max: number,
) {
  if (!Object.hasOwn(source, key)) return;
  const value = Number(source[key]);
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`invalid ${key}`);
  target[key] = value;
}

function copyNullableInteger(
  source: Record<string, unknown>,
  target: Record<string, unknown>,
  key: string,
  min: number,
  max: number,
) {
  if (!Object.hasOwn(source, key)) return;
  if (source[key] === null) {
    target[key] = null;
    return;
  }
  copyInteger(source, target, key, min, max);
}

function copyText(
  source: Record<string, unknown>,
  target: Record<string, unknown>,
  key: string,
  maxLength: number,
  nullable: boolean,
) {
  if (!Object.hasOwn(source, key)) return;
  if (source[key] === null && nullable) {
    target[key] = null;
    return;
  }
  if (typeof source[key] !== "string") throw new Error(`invalid ${key}`);
  target[key] = source[key].trim().slice(0, maxLength) || null;
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const length = Number(request.headers.get("content-length") ?? 0);
  if (length > 64_000) throw new Error("payload too large");
  return await request.json();
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}
