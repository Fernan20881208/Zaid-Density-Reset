import {
  calculateTokenExpiry,
  generateLicenseKey,
  isValidLicenseKey,
  normalizeLicenseKey,
  sha256Hex,
  signLicenseToken,
  verifyLicenseToken,
} from "./logic.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("normaliza keys pegadas, minúsculas y con espacios", () => {
  const expected = "DR-7K4P-M9QX-2W8N-R5TY";
  assert(normalizeLicenseKey("dr-7k4p-m9qx-2w8n-r5ty") === expected, "minúsculas");
  assert(normalizeLicenseKey("  DR-7K4P-M9QX-2W8N-R5TY  ") === expected, "espacios");
  assert(normalizeLicenseKey("DR 7K4P M9QX 2W8N R5TY") === expected, "separadores");
  assert(isValidLicenseKey(expected), "formato válido");
});

Deno.test("genera 100 keys criptográficamente aleatorias con el formato correcto", () => {
  const keys = new Set(Array.from({ length: 100 }, () => generateLicenseKey()));
  assert(keys.size === 100, "no debe repetir dentro del lote de prueba");
  for (const key of keys) assert(isValidLicenseKey(key), `formato inválido: ${key}`);
});

Deno.test("SHA-256 es estable para la key normalizada", async () => {
  const a = await sha256Hex(normalizeLicenseKey("dr-7k4p-m9qx-2w8n-r5ty"));
  const b = await sha256Hex("DR-7K4P-M9QX-2W8N-R5TY");
  assert(a === b && a.length === 64, "hash incorrecto");
});

Deno.test("token firmado valida solo para el dispositivo correcto", async () => {
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    licenseId: "11111111-1111-4111-8111-111111111111",
    deviceHash: "a".repeat(64),
    issuedAt: now,
    exp: now + 3600,
    nonce: crypto.randomUUID(),
  };
  const token = await signLicenseToken(payload, "test-secret-not-for-production");
  const valid = await verifyLicenseToken(token, "test-secret-not-for-production", "a".repeat(64), now);
  assert(valid.ok, "token válido rechazado");
  const otherDevice = await verifyLicenseToken(token, "test-secret-not-for-production", "b".repeat(64), now);
  assert(!otherDevice.ok && otherDevice.code === "DEVICE_MISMATCH", "device binding no aplicado");
});

Deno.test("token expirado es rechazado", async () => {
  const now = Math.floor(Date.now() / 1000);
  const token = await signLicenseToken({
    licenseId: "11111111-1111-4111-8111-111111111111",
    deviceHash: "c".repeat(64),
    issuedAt: now - 100,
    exp: now - 1,
    nonce: crypto.randomUUID(),
  }, "test-secret-not-for-production");
  const result = await verifyLicenseToken(token, "test-secret-not-for-production", "c".repeat(64), now);
  assert(!result.ok && result.code === "TOKEN_EXPIRED", "debe detectar expiración");
});

Deno.test("token nunca sobrevive a la expiración de una licencia temporal", () => {
  const now = 1_800_000_000;
  const licenseExpires = new Date((now + 1800) * 1000).toISOString();
  assert(calculateTokenExpiry(now, 168, licenseExpires) === now + 1800, "debe limitarse por la licencia");
  assert(calculateTokenExpiry(now, 168, null) === now + 168 * 3600, "permanente usa lifetime del token");
});
