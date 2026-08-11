import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.111.0";

const SUPABASE_URL = requiredEnv("SUPABASE_URL");
const SUPABASE_SERVICE_ROLE_KEY = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
const EXPECTED_REPOSITORY = "Fernan20881208/Zaid-Density-Reset";
const EXPECTED_REF = "refs/heads/main";
const EXPECTED_WORKFLOW_REF = `${EXPECTED_REPOSITORY}/.github/workflows/release.yml@${EXPECTED_REF}`;
const EXPECTED_AUDIENCE = "density-reset-release";
const GITHUB_ISSUER = "https://token.actions.githubusercontent.com";
const GITHUB_JWKS = "https://token.actions.githubusercontent.com/.well-known/jwks";
const ALLOWED_EVENTS = new Set(["push", "workflow_dispatch"]);

const service = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return json({ success: false, code: "METHOD_NOT_ALLOWED" }, 405);
  }

  try {
    const authorization = request.headers.get("authorization") ?? "";
    if (!authorization.startsWith("Bearer ")) {
      return json({ success: false, code: "UNAUTHORIZED" }, 401);
    }

    const token = authorization.slice(7).trim();
    const claims = await verifyGitHubOidc(token);
    if (
      claims.repository !== EXPECTED_REPOSITORY ||
      claims.ref !== EXPECTED_REF ||
      claims.workflow_ref !== EXPECTED_WORKFLOW_REF ||
      typeof claims.event_name !== "string" ||
      !ALLOWED_EVENTS.has(claims.event_name)
    ) {
      console.error("release-signing rejected claims", {
        repository: claims.repository,
        ref: claims.ref,
        workflow_ref: claims.workflow_ref,
        event_name: claims.event_name,
      });
      return json({ success: false, code: "FORBIDDEN" }, 403);
    }

    const { data, error } = await service.rpc("get_android_release_signing_bundle");
    if (error || !data) throw error ?? new Error("signing bundle missing");

    const bundle = data as Record<string, unknown>;
    const keystoreBase64 = requireString(bundle, "keystore_base64");
    const storePassword = requireString(bundle, "store_password");
    const keyAlias = requireString(bundle, "key_alias");
    const keyPassword = requireString(bundle, "key_password");
    const certSha256 = requireString(bundle, "cert_sha256").toLowerCase();

    if (!/^[0-9a-f]{64}$/.test(certSha256)) {
      throw new Error("invalid signing certificate digest");
    }

    return json({
      success: true,
      keystore_base64: keystoreBase64,
      store_password: storePassword,
      key_alias: keyAlias,
      key_password: keyPassword,
      cert_sha256: certSha256,
    });
  } catch (error) {
    console.error("release-signing", error instanceof Error ? error.message : "unknown");
    return json({ success: false, code: "UNAUTHORIZED" }, 401);
  }
});

type Claims = Record<string, unknown> & {
  repository?: string;
  ref?: string;
  workflow_ref?: string;
  event_name?: string;
};

async function verifyGitHubOidc(token: string): Promise<Claims> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("invalid jwt");

  const header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0]))) as Record<string, unknown>;
  const claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1]))) as Claims;
  if (header.alg !== "RS256" || typeof header.kid !== "string") {
    throw new Error("unsupported jwt header");
  }

  const jwksResponse = await fetch(GITHUB_JWKS, {
    headers: { "Accept": "application/json" },
    redirect: "error",
  });
  if (!jwksResponse.ok) throw new Error("github jwks unavailable");
  const jwks = await jwksResponse.json() as { keys?: Array<JsonWebKey & { kid?: string }> };
  const jwk = jwks.keys?.find((candidate) => candidate.kid === header.kid);
  if (!jwk) throw new Error("github signing key not found");

  const publicKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const verified = await crypto.subtle.verify(
    { name: "RSASSA-PKCS1-v1_5" },
    publicKey,
    base64UrlDecode(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!verified) throw new Error("invalid github oidc signature");

  const now = Math.floor(Date.now() / 1000);
  if (claims.iss !== GITHUB_ISSUER) throw new Error("invalid issuer");
  const audience = claims.aud;
  const audienceOk = audience === EXPECTED_AUDIENCE ||
    (Array.isArray(audience) && audience.includes(EXPECTED_AUDIENCE));
  if (!audienceOk) throw new Error("invalid audience");
  if (typeof claims.exp !== "number" || claims.exp <= now) throw new Error("expired token");
  if (typeof claims.nbf === "number" && claims.nbf > now + 30) throw new Error("token not active");
  if (typeof claims.iat === "number" && claims.iat > now + 30) throw new Error("invalid issued-at");

  return claims;
}

function base64UrlDecode(input: string): Uint8Array {
  const normalized = input.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function requireString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`missing ${key}`);
  }
  return value;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store, max-age=0",
      "Pragma": "no-cache",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}
