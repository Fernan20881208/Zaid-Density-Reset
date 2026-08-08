# Density Reset — sistema de licencias

## Arquitectura

```text
Android (com.zaidnavarro.ds)
        │ HTTPS
        ▼
Supabase Edge Function: license-api
        │ service role solo en servidor
        ▼
PostgreSQL + RLS

Density Reset Admin
        │ Supabase Auth
        ▼
license-api → comprobación profiles.role = admin
```

La aplicación Android nunca contiene `SUPABASE_SERVICE_ROLE_KEY`, contraseña de administrador, contraseña de base de datos ni secreto de firma. El panel usa únicamente la clave pública/publishable de Supabase y un access token obtenido con Supabase Auth.

## 1. Crear/configurar Supabase

Crea un proyecto Supabase y conserva estos valores en un entorno seguro:

```env
SUPABASE_URL=
SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=
LICENSE_SIGNING_SECRET=
```

`SUPABASE_URL` y la publishable/anon key son públicas y pueden usarse en un cliente. `SUPABASE_SERVICE_ROLE_KEY` y `LICENSE_SIGNING_SECRET` son secretos exclusivamente del backend.

Este repositorio incluye:

```text
supabase/migrations/202608080001_license_system.sql
supabase/migrations/202608080002_license_signing_secret.sql
supabase/functions/license-api/
```

Aplica las migraciones en orden. La segunda migración crea un secreto de firma aleatorio con `gen_random_bytes()` como fallback del lado servidor; nunca se expone a `anon` ni `authenticated`.

## 2. Desplegar la Edge Function

Despliega `supabase/functions/license-api` como `license-api`. Las rutas públicas de activación y validación implementan su propia autenticación de licencia; las rutas `/admin/*` comprueban un access token real de Supabase Auth y después `profiles.role = 'admin'`.

En producción usa exclusivamente HTTPS.

Endpoint esperado:

```text
https://PROJECT_REF.supabase.co/functions/v1/license-api
```

## 3. Crear la cuenta ADMIN

Crea un usuario mediante Supabase Auth usando correo y contraseña. Después asigna el rol administrativo por SQL desde un entorno administrativo:

```sql
insert into public.profiles(user_id, role)
select id, 'admin'
from auth.users
where email = 'TU_CORREO_ADMIN'
on conflict (user_id) do update set role = excluded.role;
```

No pongas la contraseña en JavaScript ni en el repositorio.

## 4. Panel web

El panel está en `admin/`.

Configura `admin/config.js` con valores públicos:

```js
export const config = {
  SUPABASE_URL: "https://PROJECT_REF.supabase.co",
  SUPABASE_ANON_KEY: "sb_publishable_...",
  LICENSE_API_URL: "https://PROJECT_REF.supabase.co/functions/v1/license-api",
};
```

Puede publicarse como sitio estático en GitHub Pages, Netlify, Cloudflare Pages o cualquier hosting HTTPS. El acceso a datos sigue protegido por Supabase Auth y por la comprobación server-side del rol admin.

## 5. Configurar Android

El endpoint se resuelve mediante la propiedad/variable `LICENSE_API_URL`; si no existe, `app/build.gradle.kts` utiliza el endpoint de producción configurado en el proyecto.

Para una compilación distinta:

```bash
./gradlew assembleDebug -PLICENSE_API_URL=https://PROJECT_REF.supabase.co/functions/v1/license-api
```

Android guarda el `licenseToken` cifrado con Android Keystore. DataStore guarda únicamente estado no sensible como expiración y fecha de última validación correcta.

## 6. Generar la primera key

1. Inicia sesión en Density Reset Admin.
2. Abre **Generar licencia**.
3. Elige cantidad, duración, inicio de duración, dispositivos, etiqueta y notas.
4. Pulsa **GENERAR**.
5. Copia o exporta las keys inmediatamente.

Las keys completas solo se devuelven durante esa generación. PostgreSQL almacena únicamente `SHA-256(normalizedKey)`, prefix/suffix para visualización y metadatos.

## 7. Activación Android

Android envía únicamente:

```json
{
  "key": "DR-XXXX-XXXX-XXXX-XXXX",
  "deviceHash": "SHA256...",
  "appVersion": 11,
  "packageName": "com.zaidnavarro.ds"
}
```

El servidor normaliza y hashea la key, valida estado/expiración/device limit, registra la primera activación y devuelve un token firmado. La key original no se conserva en Android después de la activación.

## 8. Validación y modo offline

- Validación al abrir la app.
- Revalidación aproximadamente cada 20 minutos mientras la app está activa.
- Gracia offline predeterminada: 12 horas desde la última validación correcta.
- Una licencia temporal jamás puede superar `expires_at` durante el modo offline.
- Revocación y deshabilitación se detectan en la siguiente validación online.

## 9. RLS y seguridad

RLS está activado. `anon` y `authenticated` no tienen CRUD directo sobre `licenses`, `license_devices`, `license_settings` ni rate limits. Las operaciones de Android pasan exclusivamente por la Edge Function. Las operaciones administrativas también pasan por la Edge Function y exigen rol admin.

Los logs del backend no imprimen key completa, token, device hash completo, Authorization, service role ni secretos de firma.

## 10. Pruebas

Android:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Backend:

```bash
deno test supabase/functions/license-api/logic.test.ts
```

Pruebas SQL de flujo:

```text
supabase/tests/license_system.sql
```

Casos cubiertos por la implementación/pruebas: key válida, incorrecta, expirada, revocada, deshabilitada, permanente, 1 día, 30 días, device mismatch, reset device, reactivación, gracia offline válida/caducada, rate limit, token expirado, reinicio de aplicación, logout, generación individual, generación masiva y exportación CSV.
