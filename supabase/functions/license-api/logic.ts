export const KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const KEY_PATTERN = /^DR-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/;

export type LicenseTokenPayload = {
  licenseId: string;
  deviceHash: string;
  issuedAt: number;
  exp: number;
  nonce: string;
};

export function normalizeLicenseKey(raw: string): string {
  const compact = raw.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  const body = (compact.startsWith("DR") ? compact.slice(2) : compact).slice(0, 16);
  const groups = body.match(/.{1,4}/g) ?? [];
  return `DR-${groups.join("-")}`;
}

export function isValidLicenseKey(raw: string): boolean {
  return KEY_PATTERN.test(normalizeLicenseKey(raw));
}

function secureRandomIndex(maxExclusive: number): number {
  const cutoff = Math.floor(256 / maxExclusive) * maxExclusive;
  const bytes = new Uint8Array(1);
  while (true) {
    crypto.getRandomValues(bytes);
    if (bytes[0] < cutoff) return bytes[0] % maxExclusive;
  }
}

export function generateLicenseKey(): string {
  let body = "";
  for (let index = 0; index < 16; index += 1) {
    body += KEY_ALPHABET[secureRandomIndex(KEY_ALPHABET.length)];
  }
  return `DR-${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}-${body.slice(12, 16)}`;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function keyPrefix(key: string): string {
  return normalizeLicenseKey(key).split("-").slice(0, 2).join("-");
}

export function keySuffix(key: string): string {
  return normalizeLicenseKey(key).split("-").at(-1) ?? "????";
}

export function maskedKey(prefix: string, suffix: string): string {
  return `${prefix}-••••-••••-${suffix}`;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function base64UrlDecode(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

function base64UrlDecodeBuffer(value: string): ArrayBuffer {
  const bytes = base64UrlDecode(value);
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

async function importHmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

export async function signLicenseToken(payload: LicenseTokenPayload, secret: string): Promise<string> {
  const header = base64UrlEncode(new TextEncoder().encode(JSON.stringify({ alg: "HS256", typ: "DR-LICENSE" })));
  const encodedPayload = base64UrlEncode(new TextEncoder().encode(JSON.stringify(payload)));
  const input = `${header}.${encodedPayload}`;
  const signature = await crypto.subtle.sign("HMAC", await importHmacKey(secret), new TextEncoder().encode(input));
  return `${input}.${base64UrlEncode(new Uint8Array(signature))}`;
}

export async function verifyLicenseToken(
  token: string,
  secret: string,
  expectedDeviceHash: string,
  nowEpochSeconds = Math.floor(Date.now() / 1000),
): Promise<{ ok: true; payload: LicenseTokenPayload } | { ok: false; code: string }> {
  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, code: "INVALID_SESSION" };
  try {
    const [header, payloadPart, signaturePart] = parts;
    const input = `${header}.${payloadPart}`;
    const valid = await crypto.subtle.verify(
      "HMAC",
      await importHmacKey(secret),
      base64UrlDecodeBuffer(signaturePart),
      new TextEncoder().encode(input),
    );
    if (!valid) return { ok: false, code: "INVALID_SESSION" };
    const payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadPart))) as LicenseTokenPayload;
    if (typeof payload.licenseId !== "string" || typeof payload.deviceHash !== "string" || typeof payload.exp !== "number") {
      return { ok: false, code: "INVALID_SESSION" };
    }
    if (payload.deviceHash !== expectedDeviceHash) return { ok: false, code: "DEVICE_MISMATCH" };
    if (payload.exp <= nowEpochSeconds) return { ok: false, code: "TOKEN_EXPIRED" };
    return { ok: true, payload };
  } catch {
    return { ok: false, code: "INVALID_SESSION" };
  }
}

export function calculateTokenExpiry(nowEpochSeconds: number, tokenLifetimeHours: number, licenseExpiresAt: string | null): number {
  const tokenLimit = nowEpochSeconds + tokenLifetimeHours * 60 * 60;
  if (!licenseExpiresAt) return tokenLimit;
  const licenseLimit = Math.floor(new Date(licenseExpiresAt).getTime() / 1000);
  return Math.min(tokenLimit, licenseLimit);
}
